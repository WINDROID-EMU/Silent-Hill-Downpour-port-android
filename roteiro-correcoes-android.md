# Roteiro de correções — Silent Hill: Downpour (fork Android)

Contexto para o agente: este é um fork Android de um jogo recompilado estaticamente
(Xbox 360 → C++ nativo) via toolchain "rexglue". O jogo roda lento e apresenta
partes de textura que demoram a carregar e aparecem brancas. As causas foram
diagnosticadas numa sessão anterior de revisão de código. Aplique as tarefas
**na ordem abaixo**, uma por vez, compilando e testando entre elas quando possível.

Repositório: fork local do usuário (Windows → Android port de Silent Hill:
Downpour, baseado em toolchain rexglue/N64Recomp-style).

---

## Tarefa 0 — Verificar CI (pode já estar feito)

Arquivo: `.github/workflows/build-apk.yml`

Verifique se o step de checkout inicializa submódulos git. Se não:

```yaml
      - name: Checkout Repository
        uses: actions/checkout@v4
        with:
          submodules: recursive
```

Motivo: `src/android/libadrenotools` é um submódulo git (`bylaws/libadrenotools`).
Sem isso o CMake falha com "does not contain a CMakeLists.txt file".

---

## Tarefa 1 — Otimizações de build nativo

Arquivo: `src/android/app/build.gradle`

Na seção `defaultConfig > externalNativeBuild > cmake`, troque:

```gradle
cppFlags "-std=c++23", "-fexceptions", "-frtti",
         "-O3", "-fvectorize", "-fslp-vectorize",
         "-funroll-loops", "-fno-semantic-interposition"
arguments "-DANDROID_STL=c++_shared",
          "-DANDROID_PLATFORM=android-26",
          "-DDPOUR_NO_NATIVE_RENDER=1",
          "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-O3 -Wl,--gc-sections -Wl,--hash-style=gnu -Wl,-Bsymbolic-functions -Wl,-z,max-page-size=16384"
```

por:

```gradle
cppFlags "-std=c++23", "-fexceptions", "-frtti",
         "-O3", "-fvectorize", "-fslp-vectorize",
         "-funroll-loops", "-fno-semantic-interposition",
         "-flto=thin", "-DNDEBUG"
arguments "-DANDROID_STL=c++_shared",
          "-DANDROID_PLATFORM=android-26",
          "-DDPOUR_NO_NATIVE_RENDER=1",
          "-DCMAKE_BUILD_TYPE=Release",
          "-DCMAKE_SHARED_LINKER_FLAGS=-flto=thin -Wl,-O3 -Wl,--gc-sections -Wl,--icf=all -Wl,--hash-style=gnu -Wl,-Bsymbolic-functions -Wl,-z,max-page-size=16384"
```

Motivo: sem `CMAKE_BUILD_TYPE=Release`/`NDEBUG`, `assert()` fica ativo mesmo no
build "release". Sem `-flto`, perde-se otimização entre unidades de compilação
num binário C++ grande — ganho real de CPU, que é o gargalo aqui (não a GPU).

---

## Tarefa 2 — Lock de refresh rate não deve trocar resolução

Arquivo: `src/android/app/src/main/java/com/downpour/DownpourActivity.java`,
dentro de `onCreate`, bloco que escolhe o `Display.Mode` de maior taxa de
atualização.

Troque a lógica que percorre `display.getSupportedModes()` escolhendo só pelo
maior `getRefreshRate()` por uma que **primeiro filtra os modos que têm a
mesma `getPhysicalWidth()`/`getPhysicalHeight()` do `display.getMode()` atual**,
e só entre esses escolhe o de maior taxa. Pegue o modo atual com
`display.getMode()` antes do laço, compare `m.getPhysicalWidth()` e
`m.getPhysicalHeight()` contra ele dentro do laço, e ignore (`continue`) os
que não baterem.

Motivo: em alguns aparelhos o modo de maior Hz vem numa resolução diferente
(geralmente menor). Sem esse filtro, o lock de refresh rate muda a resolução
do jogo sem o usuário pedir.

---

## Tarefa 3 — CRÍTICO: bug de thread affinity que explica lentidão + textura branca

Arquivo: `src/android/rex_app.cpp`

Localize `PerformanceThreadWrapper` (é chamada de dentro do hook de
`pthread_create` que intercepta **toda** thread nova criada pelo runtime via
patch de GOT). Hoje ela chama `ConfigurePerformanceThread()` para cada thread
nova, o que:

- prende **todas** as threads (renderização, streaming/decodificação de
  textura, I/O, áudio, pools de worker) nos mesmos 3-4 núcleos "big/prime";
- e ainda dá `nice -10` (prioridade alta) pra todas elas igualmente.

Isso faz as threads competirem entre si pelos mesmos núcleos em vez de
distribuir carga — resultado: lentidão geral (núcleos "little" ociosos,
throttling térmico nos poucos núcleos usados) **e** stalls intermitentes onde
a thread de streaming de textura fica sem CPU bem na hora que a GPU precisa
do dado, aparecendo como a textura branca/placeholder relatada.

**Correção**: remova a chamada a `ConfigurePerformanceThread()` de dentro de
`PerformanceThreadWrapper` (o wrapper aplicado a toda thread nova). Deixe
`ConfigurePerformanceThread()` sendo chamada **apenas uma vez**, para a thread
principal, no `constructor` de inicialização (`InitBionicPthreadFix`, que já
chama essa função diretamente uma vez após instalar os hooks). Não pine nem
priorize nenhuma outra thread — deixe o agendador do Android (que já entende
big.LITTLE) cuidar do resto.

Adicione um comentário no local explicando por que a chamada foi removida do
wrapper (para não ser reintroduzida por engano depois).

---

## Tarefa 4 — Diagnóstico: driver suporta compressão BC (DXT) nativamente?

Xbox 360 usa texturas DXT1/DXT3/DXT5 (= BC1/BC2/BC3). GPUs Adreno
historicamente só decodificam ATC/ETC2/ASTC em hardware, não BC. Se o driver
ativo (Turnip ou sistema) não reportar suporte a BC, isso por si só explica
texturas ausentes/brancas, independente do fix da Tarefa 3.

### 4a. Header `src/android/downpour_driver.h`

Adicione a declaração:

```cpp
// Diagnostic only: creates a throwaway VkInstance against whichever driver is
// currently active (Turnip or system) and logs to logcat (tag "GpuCaps")
// whether the GPU/driver reports support for BC (DXT), ETC2 and ASTC texture
// compression. Does not affect rendering.
void LogTextureCompressionSupport();
```

### 4b. Implementação `src/android/downpour_driver.cpp`

Dentro do bloco `#if defined(__ANDROID__)` (mesmo namespace `downpour::driver`),
implemente `LogTextureCompressionSupport()`:

1. `dlopen("libvulkan.so", RTLD_NOW)` — propositalmente passa pelo mesmo hook
   de `dlopen` já instalado neste arquivo, então reflete o driver realmente
   ativo (Turnip ou sistema).
2. Resolva `vkGetInstanceProcAddr` via `dlsym`.
3. Crie uma `VkInstance` mínima (`VkApplicationInfo` + `VkInstanceCreateInfo`,
   sem extensões — só precisa enumerar physical devices e ler features).
4. `vkEnumeratePhysicalDevices` → para cada device, `vkGetPhysicalDeviceFeatures`
   e `vkGetPhysicalDeviceProperties`.
5. Logue com `__android_log_print` (tag `"DownpourGpuCaps"` ou similar):
   nome do device, `features.textureCompressionBC`,
   `features.textureCompressionETC2`, `features.textureCompressionASTC_LDR`.
   Se `textureCompressionBC` for falso, logue um aviso explícito dizendo que
   os assets do Xbox 360 são BC1/2/3 e isso é candidato forte a explicar
   textura ausente/branca.
6. Destrua a instância no final.

Inclua `#include <vulkan/vulkan.h>` e `#include <vector>` no topo do arquivo
(o header do Vulkan já está disponível via SDK, outros arquivos do projeto
já o incluem transitivamente).

Adicione também o stub vazio `void LogTextureCompressionSupport() {}` no
bloco `#else` (não-Android) do mesmo arquivo, ao lado dos outros stubs.

### 4c. Chamar o diagnóstico no boot

Arquivo `src/android/downpour_main_android.cpp`, logo após a chamada
existente a `downpour::driver::InitializeDriver()` (dentro do
`#if defined(__ANDROID__)`), adicione:

```cpp
downpour::driver::LogTextureCompressionSupport();
```

---

## Tarefa 5 — Rodar e interpretar o resultado

1. Compile e rode no aparelho de teste.
2. Capture o log: `adb logcat | grep -i gpucaps` (ajuste a tag conforme o
   nome usado na Tarefa 4b).
3. **Se `textureCompressionBC=0`**: driver/hardware não decodifica BC nativamente.
   Ordem de ação recomendada:
   a. Testar com o driver Vulkan **do sistema** em vez do Turnip (toggle já
      existe no app, em Configurações de driver). Se o branco sumir/diminuir,
      o problema é específico do Turnip nesse aparelho — documentar isso como
      recomendação padrão para dispositivos com esse sintoma.
   b. Verificar se existe uma versão mais nova do submódulo/dependência
      `rexglue-sdk` que já implemente transcodificação de textura BC→ETC2/EAC
      em tempo de carga (outros ports Android baseados nesse mesmo SDK já
      implementaram isso). Se existir, atualizar a dependência antes de
      tentar qualquer coisa manual.
   c. Só se (a) e (b) não resolverem: considerar implementar transcodificação
      própria interceptando upload de textura no nível do hook Vulkan — tratar
      como item de esforço alto, não como primeira tentativa.
4. **Se `textureCompressionBC=1`**: o hardware suporta; o problema não é
   formato. Nesse caso a correção da Tarefa 3 (thread affinity) é a principal
   candidata a já ter resolvido ou reduzido bastante o sintoma. Se persistir,
   seria um bug do runtime fechado (`librexruntimerd.so`/`librexgpu-xenosrd.so`)
   e o log da Tarefa 4 vira evidência útil para reportar upstream no
   repositório do `rexglue-sdk`.

---

## Checklist final de validação

- [ ] CI builda sem erro de submódulo
- [ ] APK compila em Release com as novas flags (LTO, NDEBUG)
- [ ] Refresh rate lock não muda mais a resolução em nenhum device de teste
- [ ] Log confirma que só a thread principal recebe afinidade/prioridade elevada
- [ ] Log de `GpuCaps` capturado e decisão tomada conforme Tarefa 5
- [ ] Sessão de jogo de pelo menos 10-15 min testando cenas antes problemáticas,
      comparando frequência do "branco" antes/depois
