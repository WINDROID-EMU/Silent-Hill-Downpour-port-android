---
name: rexglue-android-porting
description: Use this skill whenever the user wants to port a static-recompilation game project (Xenon/PowerPC Xbox 360 recomps built on the "rexglue"/N64Recomp-style toolchain — e.g. UnleashedRecomp, DownpourRecomp, Skate3Recomp, SonicRecomp-style forks, or any *Recomp project) that currently only targets Windows/Linux/macOS (SDL2/SDL3 + Vulkan/D3D12 desktop build) to Android. Trigger this for requests like "porta esse recomp para Android", "transforma esse projeto SDL/Vulkan em APK", "por que meu jogo recompilado não roda no celular", "analisa esse repositório rexglue e diz o que falta pra Android", or any task involving NDK cross-compilation, Termux/vcpkg-android toolchains, Turnip/Adreno Vulkan drivers on mobile, ARM64 memory-page-size (16 KB) issues, or adapting a desktop C++ game recomp to run inside an Android Activity. This skill encodes the real evolution timeline and technical decisions of two independent Android ports — SansNope/UnleashedRecomp-Android (Fable/Claude-assisted fork of hedge-dev's UnleashedRecomp) and Buku313/Skate3-Mobile (fork of mchughalex's Skate3Recomp) — as a reusable phase-by-phase playbook, not just a one-off case study.
---

# Portando recompiladores estáticos (rexglue-style) de Windows/Linux/macOS para Android

Esta skill destila o histórico real e completo de commits/releases de **dois projetos
independentes** que fizeram exatamente esse tipo de port, e generaliza os dois em um
roteiro único:

1. **SansNope/UnleashedRecomp-Android** — fork Android do **UnleashedRecomp** (hedge-dev),
   recompilação estática do Sonic Unleashed de Xbox 360. Fonte desktop: SDL2 + Vulkan
   (bundle de driver Turnip), Windows/Linux.
2. **Buku313/Skate3-Mobile** — fork Android do **Skate3Recomp** (mchughalex), recompilação
   estática do Skate 3 de Xbox 360. Fonte desktop: SDL3, com renderer nativo que já
   suporta **Direct3D 12 *e* Vulkan** simultaneamente, Windows/Linux/macOS.

Os dois usam o mesmo SDK-base (**rexglue/rexglue-sdk**, um runtime derivado do Xenia),
mas partiram de pontos de maturidade gráfica diferentes — um caso valida o outro e,
juntos, mostram o que é comum a **qualquer** port rexglue→Android e o que muda
dependendo do estado do backend gráfico de origem.

Não é um how-to genérico de "compilar C++ pro NDK". É o mapa de decisões, na ordem
em que elas realmente precisaram ser tomadas nesses dois projetos, com o motivo de
cada uma.

## Quando NÃO usar esta skill

- Se o jogo-alvo já tem um port Android oficial ou comunitário maduro (ex.: emuladores
  Xenia-Android, RPCS3 mobile) — verifique primeiro se reinventar a roda é necessário.
- Se o projeto não é uma recompilação estática (é um emulador dinâmico) — a arquitetura
  de portabilidade é diferente (JIT, threading de CPU emulada), esta skill assume
  código PowerPC já traduzido para C++ nativo em tempo de build.
- Esta skill não ensina a burlar DRM ou distribuir arquivos de jogo com copyright —
  todo o fluxo assume que o usuário fornece seu próprio dump legal do jogo.

## Linha do tempo real do projeto de referência (o que realmente aconteceu, em ordem)

Esta é a evidência empírica por trás do playbook abaixo. Datas de 04/07/2026 a 12/07/2026 —
o port inteiro, do zero ao estável, levou ~8 dias com um humano supervisionando um
agente de IA (Claude/Fable) fazendo a maior parte do trabalho de código.

1. **v0.0.1 (04/07)** — Boot mínimo. APK simplesmente inicia, pede ao usuário para
   colocar os arquivos do jogo via PC (Android 14+ bloqueia acesso direto à pasta
   `Android/`), e já embute um driver Vulkan customizado (Turnip) porque o sistema
   não tinha um utilizável. Prova de conceito: "o loop de render desktop consegue
   desenhar um frame numa Activity Android".
2. **v0.0.3 (05/07)** — Amplia a lista de GPUs suportadas pelo driver bundled
   (Adreno 710/720/722/725/732/750). Ou seja: o maior gargalo inicial não foi o
   código do jogo, foi **qual driver Vulkan o Android tem disponível**.
3. **v0.0.4 (08/07)** — Primeira camada de UX real: controles touch, ícone do app,
   nome do pacote. Sinaliza a transição de "roda tecnicamente" para "é jogável".
4. **v0.1.4 (08/07)** — Editor de posição/tamanho dos controles touch. Refinamento
   incremental de UX, não mudança estrutural.
5. **v0.2.1 (11/07)** — Ponto de virada técnico:
   - Suporte experimental a **GPUs Mali** (Valhall, Vulkan 1.3 nativo do sistema,
     sem precisar de driver customizado) — o app passa a detectar o fornecedor da
     GPU e pular o driver bundled Adreno quando não é Adreno.
   - **Transcodificação de texturas BC1-BC5/BC7 para ETC2/EAC em tempo de carga**
     na CPU, para GPUs sem suporte nativo a BC — com fallback para RGBA puro se
     nem ETC2 existir. Isso resolve o problema mais comum de recomps de console:
     os assets originais usam formatos de compressão de textura que o desktop
     (DirectX/PC) suporta universalmente mas o Android não.
   - Import de pacotes de driver via `.zip` (ExynosTools, AdrenoToolsDrivers).
   - Diagnóstico de crash: log.txt passa a abrir com resumo do device (modelo,
     SoC, versão do Android, GPU/HAL, ABI, RAM) e resolve endereços de sinal fatal
     para módulo+offset — permitindo debugar sem adb/root, só com o arquivo de log
     que o usuário mesmo envia.
6. **v0.3.0 (11/07)** — Reestruturação de infraestrutura, não de gameplay:
   - Launcher unificado em Java/Kotlin que valida a instalação antes de iniciar o
     código nativo (antes era um entrypoint SDL direto).
   - `Android/media/<package>` como fallback de armazenamento acessível por
     gerenciador de arquivos, junto com `Android/data/` interno.
   - Correção de recriação de swapchain (erro `VK_ERROR_NATIVE_WINDOW_IN_USE_KHR`
     comum quando o app volta de background no Android).
   - **CI: GitHub Actions cross-compilando ARM64 com NDK + vcpkg**, com checkout
     privado dos arquivos de jogo, cache (ccache), assinatura opcional de release,
     e `.gitignore` para tudo que é gerado a partir dos arquivos do jogo do usuário
     (para nunca vazar assets protegidos por copyright no repositório).
7. **v0.4.0 (12/07)** — Controle de câmera por toque (swipe ou stick virtual) e,
   principalmente, **hardening de estabilidade sem ainda ter a causa raiz resolvida**:
   a página nula do processo guest passa a ser legível/gravável (retorna zeros em vez
   de crashar em ponteiros quebrados 0/-1), chamadas indiretas para endereços fora do
   código recompilado são puladas e logadas em vez de saltar para endereço selvagem.
   Isso é uma lição central: **em recomp, muitos crashes vêm de ponteiros/branches
   inválidos gerados pela tradução PowerPC→nativo, e a mitigação correta costuma ser
   tornar esses casos não-fatais e logáveis, não "consertar" cada um individualmente**.
8. **v0.5.0 (12/07)** — Polimento final: instalação de arquivos de jogo e mods
   direto pelo app (sem precisar de PC), D-pad automático em menus, botão único de
   SKIP em cutscenes, opções gráficas específicas para hardware fraco (qualidade de
   textura em 3 níveis, toggle de reflexos planares, resolução de sombra mais baixa),
   e tradução da interface do launcher para os idiomas do próprio jogo.

## Segunda evidência empírica: Skate3-Mobile (fork Android de Skate3Recomp)

Este caso é mais recente e **parte de um ponto de maturidade gráfica diferente**: o
`Skate3Recomp` upstream, desde sua v2.0.0, já roda em Windows, Linux e macOS com um
**renderer nativo que suporta Direct3D 12 e Vulkan ao mesmo tempo** (não emula mais a
GPU do Xbox 360; reconstrói os desenhos via hooks que observam envios de malha,
texturas, estado de shader e constantes, e um renderer nativo próprio redesenha isso
com shaders Vulkan/D3D12). Isso muda o ponto de partida do port: em vez de escrever um
backend gráfico do zero (caso do Unleashed original e do DownpourRecomp, que só tinham
D3D12), o fork Android **reaproveitou o backend Vulkan que já existia no Linux/macOS**.

Fatos técnicos confirmados no repositório e relevantes para o playbook:

- **Requisitos mínimos explícitos de CPU, não só de GPU**: Android 13+, `arm64-v8a`,
  e **ARMv8.2 com extensões FP16 e dot-product** obrigatórias. O upstream desktop
  também documenta ter removido um requisito de AVX2 em algum momento — ou seja,
  requisitos de baseline de CPU (não só de driver gráfico) são algo que recomps
  frequentemente têm e que precisa virar um gate de compatibilidade explícito no
  launcher Android, não só um "roda ou não roda".
- **Suporte a páginas de memória de 4 KB e 16 KB do Android** é citado como item de
  status concluído. Isso é uma armadilha real e pouco óbvia: muitos devices Android
  recentes (a partir do Android 15, principalmente linha Pixel) usam página de 16 KB
  em vez de 4 KB, e recomps costumam depender pesadamente de `mmap`/layout de memória
  guest com suposições de página de 4 KB (herdadas de x86/Linux desktop). Isso quebra
  silenciosamente ou trava no boot se não for tratado.
- **Nenhuma alocação de memória executável em runtime**: o release notes do upstream
  menciona que o runtime "never used [executable memory] — all game code is compiled
  at build time", o que virou até uma vantagem para reduzir falsos positivos de
  antivírus no Windows. No Android isso também importa: políticas W^X mais estritas
  em versões recentes do sistema penalizam apps que alocam memória RWX (comum em
  JITs); um recomp estático bem-comportado não tem esse problema por construção —
  mas vale confirmar que nenhuma parte do runtime host (ex.: trampolins de hook)
  tenta fazer isso.
- **Instalador dentro do app baixa e verifica patches oficiais pela rede**: o app
  não só extrai o ISO fornecido pelo usuário via SAF — ele também **baixa a Title
  Update 3 oficial de uma fonte própria, verifica tamanho e SHA-256 de dois pacotes
  de patch, e só então gera os arquivos derivados** (`default.xexp`,
  `EAWebkit.xexp`) necessários para rodar. Há fallback manual de seleção de arquivo
  se o download automático falhar. Isso estende a Fase 3 do playbook: em jogos que
  dependem de um title update oficial para rodar corretamente, o app-alvo pode (e
  talvez deva) automatizar a obtenção e verificação criptográfica desse patch, não
  só a extração do dump bruto.
- **Autoatualização do APK fora da Play Store**: o launcher consulta um manifesto
  hospedado no próprio repositório, compara código de versão, baixa e verifica o
  APK novo com SHA-256, e entrega ao instalador de pacotes do Android — sem nunca
  substituir a pasta de jogo já instalada. Padrão útil para distribuição fora de loja
  oficial (sideload), que é o caso comum desses projetos de recomp.
- **Perfis de dispositivo nomeados, não só toggles soltos**: em vez de expor cada
  opção gráfica isoladamente, o app embute dois perfis prontos e alternáveis num só
  APK — "RG406V / Performance" (cena interna em 512×288, LOD reduzido, sem
  grama/pós-processamento caro) e "High-End / Quality" (720p interno, LOD original,
  MSAA 2x, sombras, SSAO, bloom, volumétricos) — com um botão único "Apply &
  Restart". Isso é uma evolução do que o Unleashed fez com toggles individuais de
  textura/sombra/reflexo: **agrupar as opções gráficas em 2–3 perfis nomeados por
  classe de hardware** reduz a chance do usuário configurar uma combinação ruim.
- **Import de driver Vulkan sem root via biblioteca dedicada**: usa
  [libadrenotools](https://github.com/bylaws/libadrenotools) para carregar pacotes
  ADPKG (AdrenoTools) da pasta interna privada do app antes de criar a instância
  Vulkan, com o driver do sistema como fallback permanente e padrão. Mesmo padrão
  de import de driver do Unleashed, mas com a biblioteca específica nomeada — vale
  reutilizá-la em vez de reimplementar o parsing de ADPKG.
- **SDL3, não SDL2**, para janela/controller/áudio no Android, com uma ponte nativa
  de XInput que funde o overlay de touch multiplayer com o "player one" do jogo
  (inclusive dentro do menu do próprio recomp). Confirma que SDL3 já é uma opção
  viável e madura para o backend Android de um recomp novo, não só SDL2.
- **Scripts de build locais para quem não é desenvolvedor** (`.command` de
  duplo-clique num Mac com Apple Silicon) como alternativa/complemento ao CI de
  GitHub Actions — últil quando o público-alvo inclui pessoas que só querem gerar
  um APK personalizado sem terminal.
- **Gerência de dependência via submódulo git** (`git submodule update --init
  --recursive`) para o fork do rexglue SDK específico do jogo, em vez de
  vcpkg/pacote instalado — um padrão alternativo de build igualmente válido, a
  depender de como o projeto-fonte já organiza suas dependências.
- **Compatibilidade retroativa de path de instalação**: instalações antigas de
  testador em `/sdcard/skate3` continuam sendo detectadas e funcionando, mesmo
  depois do app migrar para armazenamento sandboxed
  (`Android/data/<pkg>/files/game/`) como caminho padrão para novos usuários.

## O playbook generalizado: fases de portar qualquer *Recomp/rexglue para Android

Aplique estas fases em ordem. Cada fase corresponde a um "salto" real observado
acima. Não pule fases — a ordem existe porque cada uma depende da anterior
funcionando pelo menos minimamente.

### Fase 0 — Reconhecimento do projeto-fonte

Antes de tocar em código, o agente deve mapear:

1. Qual runtime gráfico o projeto usa hoje, e **isso é a decisão que mais muda o
   tamanho do trabalho inteiro**:
   - Se o projeto **já tem um backend Vulkan funcional em Linux/macOS** (mesmo que
     D3D12 seja o padrão no Windows, como no Skate3Recomp desde a v2.0.0) — o port
     Android reaproveita esse backend quase diretamente. Fases 1 e 2 encolhem
     bastante: o trabalho principal vira "compilar esse backend Vulkan existente
     para ARM64 + NDK", não "escrever um renderer novo".
   - Se o projeto só tem **D3D12** e nunca teve um caminho Vulkan em nenhuma
     plataforma (caso do DownpourRecomp) — **isso é bloqueio de dia 1**: alguém
     precisa portar/reescrever esse backend para Vulkan antes de qualquer coisa
     rodar no Android. Trate como a maior linha de esforço do projeto inteiro,
     não como um detalhe de plataforma.
   - Se já é **SDL2/SDL3 + Vulkan** nativamente (caso do UnleashedRecomp) — o
     caminho mais rápido dos três, é essencialmente Fase 1 do playbook padrão.
2. Requisitos mínimos de **CPU**, não só de GPU: recomps de PowerPC costumam ter
   pressupostos de instruções do host (ex.: uso ou remoção de AVX2 em builds x86-64).
   No ARM64 o equivalente a checar é suporte a **ARMv8.2 com FP16 e dot-product**
   (`sdot`/`udot`) — trate isso como um gate explícito de compatibilidade de
   dispositivo no launcher, do mesmo jeito que se trata GPU, não como algo implícito.
3. Onde fica o "guest" (código PowerPC recompilado para C++/ASM nativo) e onde
   fica o "host" (janela, input, áudio, arquivo). O guest normalmente é portável
   quase sem alteração; o host é 100% o que precisa ser reescrito.
4. Como o build system atual gera os binários. **CMake + vcpkg** é um padrão comum
   nesse ecossistema, mas **submódulo git apontando pro SDK-base (rexglue-sdk ou
   um fork específico do jogo)** é outro padrão igualmente válido — confirme qual
   dos dois o projeto usa antes de assumir um workflow de dependências. Confirme
   também se há triggers de geração de código a partir dos arquivos do jogo
   (headers de função PPC, tabelas de switch, arquivos `.xexp` derivados de title
   updates) — esses **nunca** devem ser commitados nem redistribuídos (adicione ao
   `.gitignore` desde já).
5. Liste dependências nativas (áudio, filesystem, controller API, janela) que
   são Windows/Linux/macOS-only e vão precisar de substituto Android.
6. Confirme se o runtime host aloca memória executável em algum ponto (trampolins
   de hook, JIT parcial para algum subsistema). Recomps bem-comportados compilam
   todo o código do jogo em tempo de build e nunca precisam de memória RWX — se o
   projeto-fonte for assim, isso é uma vantagem que evita atrito com políticas W^X
   mais estritas em versões recentes do Android; se não for, é um risco a mapear
   cedo.

### Fase 1 — Boot mínimo (equivalente a v0.0.1–v0.0.3)

Objetivo: só conseguir desenhar um frame na tela do celular. Não se preocupe
com jogabilidade, som ou persistência ainda.

1. Envolva o entrypoint SDL num `Activity`/`NativeActivity` Android (ou
   `android.app.GameActivity` do AGDK). Se o projeto já usa **SDL2 ou SDL3**
   puro, isso é direto — ambas as versões têm backend Android nativo maduro
   (SDL3 inclusive já foi usado em produção para janela, controller e áudio
   em pelo menos um port real desse gênero).
2. Cross-compile com **NDK + toolchain vcpkg-android** (triplet
   `arm64-android`). Trate isso como o primeiro Actions/CI job possível, mesmo
   que manual no início — recompilar C++ com dependências vcpkg é lento e
   frágil o suficiente para justificar automação cedo.
3. **Resolva Vulkan antes de qualquer outra coisa.** Muitos devices Android não
   têm driver Vulkan utilizável (Adreno mais antigos, alguns vendors OEM
   quebrados). Bundle um driver alternativo (ex.: **Turnip**, o driver Mesa
   open-source para Adreno) dentro do APK como fallback, carregável via
   `dlopen` sobre a ICD do sistema. Isso é o que resolveu o boot no dia 1 do
   projeto de referência.
4. Redirecione o carregamento dos arquivos do jogo: Android bloqueia acesso
   direto a `Android/` a partir do Android 14, então a primeira UX de
   "onde estão os arquivos do jogo" deve usar `ACTION_OPEN_DOCUMENT_TREE`/SAF
   ou orientar o usuário a copiar via PC/cabo primeiro. Não tente ler paths
   arbitrários do storage externo.
5. Critério de saída da fase: o jogo chega ao menu principal, mesmo sem input
   funcional, em pelo menos um device físico de teste.

### Fase 2 — Ampliar cobertura de GPU (equivalente a v0.0.3 e v0.2.1)

Objetivo: sair de "funciona no meu Adreno" para "funciona na maioria dos SoCs
Android em uso".

1. Detecte o vendor da GPU em runtime (`vkGetPhysicalDeviceProperties`) e
   ramifique a estratégia:
   - **Adreno**: mantenha o driver bundled (Turnip) ou o driver do sistema como
     padrão, mas também aceite drivers importados manualmente (pacotes
     AdrenoToolsDrivers/ExynosTools em `.zip`, com `meta.json` apontando a lib
     de entrada). Para carregar esse `.so` sem root, reaproveite uma biblioteca
     já pronta para isso (ex. [libadrenotools](https://github.com/bylaws/libadrenotools))
     em vez de reimplementar o parsing de ADPKG do zero; armazene o pacote
     importado na pasta interna privada do app, nunca em storage compartilhado
     (Android não executa bibliotecas nativas de lá), e mantenha o driver do
     sistema como fallback padrão e permanente caso o importado cause crash.
   - **Mali (Valhall: G610/G615/G710/G715/G720+)**: pule o driver bundled e use
     o driver Vulkan 1.3 do sistema diretamente — Mali moderno geralmente já
     tem ICD funcional. Mali Bifrost ou mais antigo normalmente não é viável.
   - **PowerVR / Xclipse / outros**: trate como "sistema" por padrão também,
     com fallback documentado, não bloqueado.
2. **Não assuma que o device suporta os mesmos formatos de textura do PC.**
   Jogos de console usam BC1–BC5/BC7 (DXTn). Se `VkFormatProperties` mostrar
   que o formato BC não é suportado:
   - Transcodifique para **ETC2/EAC** em CPU no load-time, preservando o
     footprint de memória de GPU e o espaço de cor correto (sRGB → variante
     sRGB do ETC2).
   - Se nem ETC2 existir, caia para RGBA descompactado puro como último recurso.
   - Exponha qual caminho está ativo em um overlay de diagnóstico (ajuda muito
     no suporte pós-lançamento).
3. Relaxe requisitos de features Vulkan sempre que a spec permitir: promova
   extensões antigas para o caminho "core" quando o device já é Vulkan 1.2+,
   trate `robustness2` e formatos de sampler exóticos (`MIRROR_ONCE` etc.)
   como opcionais com fallback silencioso, e nunca assuma limites de descriptor
   set do PC (ex.: 65536 texturas) — sempre faça `min(desejado, limite_do_device)`.
4. **Verifique compatibilidade com páginas de memória de 16 KB.** Vários devices
   Android recentes (a partir do Android 15, principalmente linha Pixel) usam
   página de 16 KB em vez dos 4 KB tradicionais de Linux/x86. Recomps costumam
   depender pesadamente de `mmap` para reservar o espaço de endereço guest do
   PowerPC (tipicamente 4 GB de espaço virtual reservado de uma vez), com
   suposições de alinhamento de página herdadas do desktop. Teste
   explicitamente em um device de 16 KB (ou compile com o flag do NDK que força
   esse alinhamento) antes de declarar suporte — isso costuma quebrar
   silenciosamente ou travar no boot, não dar um erro claro.
5. Critério de saída: pelo menos um device Adreno **e** um device Mali rodando
   o menu principal com texturas visualmente corretas.

### Fase 3 — Infraestrutura de instalação, storage e launcher (equivalente a v0.3.0/v0.5.0)

Objetivo: o usuário final não deveria precisar de terminal, adb ou PC para nada.

1. Construa um **launcher separado** (Activity Java/Kotlin leve) que roda antes
   do código nativo, valida a instalação do jogo, e só então lança a Activity
   nativa/SDL. Isso também é onde ficam as configurações (driver, controles,
   resolução, etc.) — não misture essa UI com o loop de render do jogo.
2. Storage: suporte pelo menos dois caminhos —
   - `context.getExternalFilesDir()` (`Android/data/<pkg>/files/...`) como
     padrão sandboxed.
   - Uma pasta em `Android/media/<pkg>/` como fallback acessível por
     gerenciador de arquivos comum, para usuários que preferem copiar os
     arquivos manualmente.
   Nunca dependa só de um path compilado fixo; resolva o diretório de arquivos
   internos em runtime.
3. Adicione instalação de arquivos de jogo (e mods, se aplicável) **de dentro
   do próprio app** via `ACTION_OPEN_DOCUMENT`/`ACTION_OPEN_DOCUMENT_TREE`,
   detectando automaticamente a estrutura de pastas dentro de um ZIP ou pasta
   solta, não importa quão aninhada esteja.
4. Se o build desktop original depende de um instalador separado (que gera uma
   pasta "patched"/processada a partir do dump bruto), replique essa etapa
   **dentro do app**, no primeiro launch, para que um dump bruto (jogo +
   update, opcionalmente DLC) seja suficiente sem PC.
5. **Se o jogo depende de um title update/patch oficial específico para rodar
   corretamente** (não só do XEX base), considere automatizar a obtenção desse
   patch dentro do próprio app: baixar de uma fonte controlada pelo projeto,
   validar tamanho e hash (SHA-256) de cada arquivo antes de usá-lo, gerar os
   artefatos derivados que o runtime espera, e oferecer um fallback manual de
   seleção de arquivo caso o download automático falhe. Isso evita depender do
   usuário achar e aplicar o patch certo por conta própria.
6. Corrija o ciclo de vida de swapchain: ao voltar de background (Android
   suspende a superfície nativa), destrua o swapchain antigo antes de tentar
   recriar, ou você terá `VK_ERROR_NATIVE_WINDOW_IN_USE_KHR` em loop.
7. Configure automação de build o quanto antes nesta fase, não depois — duas
   abordagens complementares, não excludentes:
   - **CI (GitHub Actions)**: NDK + vcpkg (ou submódulo do SDK) cross-compilando
     para `arm64-v8a`, com cache de compilação (ccache — recomps costumam ter
     árvores de tradução PPC→C++ enormes, builds do zero são inviáveis para
     iteração rápida), checkout privado de arquivos de jogo apenas se o CI
     precisar gerar código a partir deles (nunca versione esses artefatos),
     `.gitignore` explícito para toda saída derivada dos arquivos do jogo do
     usuário, e assinatura opcional de release + upload de artefato.
   - **Script de build local para não-programadores** (ex. `.command`
     duplo-clique num Mac, ou `.bat`/`.sh` com detecção automática de
     SDK/NDK/JDK instalados via Android Studio): baixa valor de automação para
     você, mas baixa muito a barreira para colaboradores que só querem gerar
     um APK personalizado com os próprios arquivos de jogo, sem usar terminal.
8. Se o público do projeto instala fora da Play Store (o caso comum aqui), considere
   um **autoatualizador simples dentro do app**: checar periodicamente um
   manifesto de versão hospedado no próprio repositório, comparar código de
   versão, baixar e verificar o APK novo por SHA-256, e entregar ao instalador
   de pacotes do Android — sem nunca tocar na pasta de jogo já instalada pelo
   usuário. Mantenha um caminho de leitura retrocompatível para instalações
   antigas em pastas legadas (ex. `/sdcard/<jogo>`) mesmo depois de migrar o
   padrão para armazenamento sandboxed, para não quebrar quem já tinha
   instalado antes da mudança.

### Fase 4 — Input, câmera e UX de toque (equivalente a v0.0.4/v0.1.4/v0.4.0/v0.5.0)

Objetivo: transformar "roda" em "é confortável de jogar com dedo".

1. Layout de controles touch editável (posição e tamanho de cada botão/stick),
   persistido entre sessões, com um modo de edição isolado (não deixe o botão
   de editar sempre visível durante o jogo).
2. Câmera por toque: ofereça pelo menos dois modos —
   - Swipe na área livre da tela (sem widget visual).
   - Stick virtual dedicado, posicionável no editor de layout.
   Garanta que dedos que começam sobre um botão/stick nunca movam a câmera, e
   que o dedo de câmera nunca dispare botões (separação de "zonas de toque").
3. Contextualize os controles por estado de jogo: D-pad automático em vez de
   analógico em telas de menu (evita luta contra deadzone), botão único de
   "SKIP" cobrindo toda a tela durante cutscenes em vez do HUD completo de
   gameplay.
4. Adapte o menu de opções gráficas ao contexto mobile: remova opções que só
   fazem sentido em desktop (tamanho de janela, monitor, fullscreen, vsync) e
   adicione as que fazem sentido só em mobile (qualidade de textura em
   degraus — ex. Full/Half/Quarter reduzindo mip base —, toggle de passes caros
   como reflexos planares, um degrau extra de resolução de sombra abaixo do
   mínimo desktop).
5. **Prefira agrupar as opções gráficas em 2–3 perfis nomeados por classe de
   hardware** (ex. "Performance"/handheld fraco vs "Quality"/celular topo de
   linha), cada um definindo de uma vez resolução interna de render, distância
   de LOD, presença de vegetação/efeitos de pós-processamento caros e nível de
   MSAA, com um único botão "aplicar e reiniciar" — em vez de expor cada cvar
   isoladamente. Isso reduz drasticamente a chance de o usuário montar uma
   combinação de opções que não roda no aparelho dele, e é mais fácil de
   validar em CI/QA (só 2–3 combinações fixas, não uma matriz combinatória).

### Fase 5 — Hardening de estabilidade e diagnóstico (equivalente a v0.2.1/v0.4.0)

Objetivo: quando (não se) o jogo travar num device que você não tem em mãos,
você precisa conseguir diagnosticar só com o que o usuário te manda.

1. No início de `log.txt`, sempre grave: modelo do device, SoC, versão do
   Android, GPU/HAL, ABI e RAM. Sem isso, todo bug report é inútil.
2. Registre handler de sinais fatais (SIGSEGV, SIGABRT etc.) que resolve o
   endereço de falha para módulo+offset, para permitir localizar o crash
   offline sem adb nem símbolos no device do usuário.
3. Trate a classe mais comum de crash específica de recomp — **ponteiros ou
   alvos de branch inválidos originados da tradução PowerPC→nativo** — como
   caso recuperável sempre que a semântica permitir:
   - Página nula do guest legível/gravável retornando zero em vez de crashar
     (para os inevitáveis ponteiros quebrados 0/-1 remanescentes da tradução).
   - Chamadas indiretas cujo alvo caia fora do código recompilado/mapeado:
     pule e logue, não salte para endereço arbitrário.
   Isso não "conserta" o bug de tradução subjacente, mas troca "crash total"
   por "artefato pontual reportável" — normalmente aceitável em builds
   comunitárias enquanto a causa raiz é investigada.
4. Adicione um watchdog de hang (thread separada monitorando o loop principal)
   para que `log.txt` capture o estado das threads mesmo sem debugger anexado.
5. Ofereça um overlay de profiler/diagnóstico opcional (desligado por padrão),
   ativável nas configurações do launcher, não sempre visível.

### Fase 6 — Localização e polimento final

1. Traduza a UI do launcher/instalador/gerenciador de mods para os mesmos
   idiomas que o próprio jogo já suporta (reaproveite a lista de idiomas do
   jogo original), seguindo o idioma do sistema ou por-app do Android.
2. Garanta que o ícone do app usa a arte original do jogo preenchendo
   corretamente o canvas de ícone adaptativo (evite ícone pequeno num círculo
   branco ou fundo preto — problema comum de ícone legado mal migrado).
3. Corrija bugs de escala em ferramentas auxiliares (ex.: um scanner de mods
   que gasta seu orçamento de arquivos dentro do conteúdo de um mod grande
   antes de ler seu manifesto — sintoma clássico de "funciona com dados de
   teste pequenos, quebra em dados reais grandes").

## Checklist de decisão rápida para o agente

Ao receber um projeto rexglue/*Recomp desktop-only para portar, responda estas
perguntas nesta ordem antes de escrever qualquer código:

1. **O backend Vulkan já existe em alguma plataforma desktop do projeto** (mesmo
   que não seja o padrão no Windows)? → se sim, o port reaproveita esse backend
   e Fases 1–2 são dias/semanas. Se o projeto só tem D3D12 e nunca teve caminho
   Vulkan em nenhum SO → bloqueio duro, alguém precisa portar/escrever esse
   backend primeiro; trate como a maior linha de esforço do projeto todo.
2. O projeto já usa SDL2/SDL3 puro para janela/input, ou tem um shell de app
   próprio sobre a base do SDK (ex. `ReXApp`/`DownpourApp`-style)? → SDL puro
   deixa a Fase 1 rápida (dias); shell próprio exige investigar se a criação de
   janela já é abstraída de forma portátil antes de estimar o esforço.
3. Quais formatos de textura os assets originais usam? Se BC/DXTn → planeje
   a Fase 2 (transcodificação ETC2) desde o início, não como afterthought.
4. O projeto tem um "instalador" desktop separado que gera assets processados,
   ou depende de um **title update oficial baixado à parte**? → replique a
   extração dentro do app (Fase 3, item 4) e, se depender de patch oficial,
   considere automatizar download+verificação por hash também (Fase 3, item 5)
   — senão o usuário vai precisar de PC mesmo depois do port pronto.
5. Existe geração de código a partir de arquivos do jogo do usuário no
   processo de build? → configure `.gitignore` e automação de build (CI e/ou
   script local) com checkout privado *antes* do primeiro commit público, não
   depois.
6. O runtime host aloca memória executável em algum ponto, ou é 100% código
   compilado em tempo de build? → se for 100% estático, documente isso como
   vantagem (evita atrito com W^X do Android); se não for, mapeie onde e
   valide cedo em um device real.

## Notas de proveniência e ética

Os dois projetos de referência são ports **não-oficiais**, feitos por comunidades
de modding (com ou sem assistência de IA) sob supervisão humana, de recomps que
por sua vez também são não-oficiais. Ao aplicar este playbook:

- Nunca inclua ou redistribua arquivos de jogo protegidos por copyright no
  repositório ou nos artefatos de CI — apenas o dump fornecido pelo próprio
  usuário final, localmente, deve tocar esses arquivos.
- Deixe claro para o usuário final que ele precisa de uma cópia legalmente
  adquirida do jogo original.
- Créditos de fork/autoria (nome dos mantenedores originais do recomp e do
  port) devem ser preservados em qualquer fork subsequente.