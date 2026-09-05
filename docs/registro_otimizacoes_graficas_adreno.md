# Registro Técnico: Otimização Gráfica Vulkan e Adaptação Adreno (ReXGlue SDK)

Este documento registra a análise técnica completa, diagnóstico e todas as modificações aplicadas na camada gráfica (Vulkan, Shaders, Pipelines e Drivers) do port Android de **Silent Hill: Downpour** baseado no **ReXGlue SDK**.

---

## 1. Contexto e Diagnóstico dos Problemas Gráficos

A GPU do Xbox 360 (**Xenos**) e as GPUs móveis Qualcomm **Adreno** (séries 6xx a 8xx) possuem filosofias arquiteturais distintas:
* **Xenos**: Renderizador unificado com ~10 MiB de eDRAM dedicada de altíssima largura de banda para ROPs (color, depth/stencil, multisampling instantâneo).
* **Adreno**: Renderizador **TBDR** (*Tile-Based Deferred Renderer*) com memória interna **GMEM** (128 KB a 1 MB por tile), ALUs escalares sensíveis à pressão de registradores (*VGPR*) e sem descompressão de hardware para texturas DXT/BC em drivers proprietários antigos.

### Principais Gargalos Diagnosticados:
1. **Texturas Brancas / Falha no Streaming de Assets**:
   - Competição e contenção de threads na CPU (worker de descompressão de textura do Unreal Engine 3 sufocado pelas threads de render).
   - Incompatibilidade de descompressão de hardware BC1-BC5 (DXT1/3/5) em drivers proprietários Qualcomm Vulkan.
2. **Desperdício de Banda e Aquecimento (Thermal Throttling)**:
   - Invalidação contínua de páginas de memória (`clear_memory_page_state`) e resolves frequentes forçando flush de tiles do GMEM para a RAM física (LPDDR4x/5).
   - Emulação de EDRAM por `Fragment Shader Interlock (FSI)` causando paralisia por contenção atômica de fragmentos.
3. **Micro-stutters na Compilação de Shaders**:
   - Falta de pré-aquecimento e compilação síncrona de pipelines SPIR-V no thread principal.
   - Pressão excessiva de registradores de vetor (VGPR) ao rodar fragment shaders em precisão total FP32.

---

## 2. Modificações e Melhorias Aplicadas no Código

### A. Otimização do Compilador e Driver Adreno (Mesa Turnip / IR3)
* **Arquivo**: `src/android/downpour_driver.cpp`
* **Mudanças Implementadas**:
  ```cpp
  // Otimizações aplicadas na inicialização do Turnip:
  setenv("TU_DEBUG", "noconform", 0);
  setenv("PAN_MESA_DEBUG", "gmem", 0);
  setenv("IR3_ENABLE_HALF_PRECISION", "1", 0);
  ```
* **Efeito**:
  - `IR3_ENABLE_HALF_PRECISION=1`: Instrui o compilador nativo Adreno (IR3) a promover variáveis e operações aritméticas de cor e fragmento para registradores FP16 (*half-precision*). Isso reduz pela metade o uso de registradores VGPR, dobra a taxa de ocupação de ondas (*wave occupancy*) e reduz aquecimento da GPU.
  - `TU_DEBUG=noconform`: Remove verificações desnecessárias de conformidade estrita de desktop, ativando caminhos de renderização direta.
  - `PAN_MESA_DEBUG=gmem`: Prioriza retenção de dados no GMEM interno.

### B. Otimização das CVARs de Renderização do ReXGlue
* **Arquivo**: `src/android/downpour_main_android.cpp`
* **Mudanças Implementadas**:
  ```cpp
  // Preservação de GMEM e caminho de Render Targets nativo:
  rex::cvar::SetFlagByName("clear_memory_page_state", "false");
  rex::cvar::SetFlagByName("render_target_path_vulkan", "host");
  rex::cvar::SetFlagByName("vulkan_dynamic_rendering", "false");
  rex::cvar::SetFlagByName("vulkan_validation_enabled", "false");
  ```
* **Efeito**:
  - `clear_memory_page_state = false`: Impede que o runtime descarte agressivamente os estados das páginas de memória, permitindo que os tiles permaneçam em cache local na GPU.
  - `render_target_path_vulkan = "host"`: Força o uso de Render Targets convencionais do host com hardware ROP da Adreno, descartando o overhead catastrófico do caminho `fsi` (*Fragment Shader Interlock*).
  - `vulkan_dynamic_rendering = false`: Preserva `VkRenderPass` estruturado com subpasses, permitindo que anexos temporários recebam `LOAD_OP_DONT_CARE` e `STORE_OP_DONT_CARE`.
  - `vulkan_validation_enabled = false`: Garante desligamento absoluto de camadas de debug e overhead de validação em produção.

### C. Pipeline Assíncrono e Limites de Memória contra OOM
* **Arquivo**: `src/android/downpour_main_android.cpp`
* **Mudanças Mantidas e Reforçadas**:
  ```cpp
  rex::cvar::SetFlagByName("async_shader_compilation", "true");
  rex::cvar::SetFlagByName("vulkan_pipeline_creation_threads", "4");
  rex::cvar::SetFlagByName("store_shaders", "true");
  rex::cvar::SetFlagByName("texture_cache_memory_limit_soft", "256");
  rex::cvar::SetFlagByName("texture_cache_memory_limit_hard", "384");
  rex::cvar::SetFlagByName("texture_cache_memory_limit_render_to_texture", "64");
  rex::cvar::SetFlagByName("native_2x_msaa", "false");
  rex::cvar::SetFlagByName("anisotropic_override", "2");
  rex::cvar::SetFlagByName("readback_resolve", "none");
  rex::cvar::SetFlagByName("readback_memexport", "false");
  rex::cvar::SetFlagByName("readback_memexport_fast", "true");
  ```

### D. Diagnóstico de Suporte a Texturas BC (DXT)
* **Arquivos**: `src/android/downpour_driver.h`, `src/android/downpour_driver.cpp`, `src/android/downpour_main_android.cpp`
* **Mudanças Implementadas**:
  - Função `LogTextureCompressionSupport()` executa na inicialização e inspeciona `textureCompressionBC`, `textureCompressionETC2` e `textureCompressionASTC_LDR` sob a tag `"DownpourGpuCaps"` no Logcat.
  - Se o driver do sistema acusar `textureCompressionBC = 0`, o usuário pode alternar no próprio launcher para o **Mesa Turnip**, que suporta e decodifica as texturas do console.

---

## 3. Guia de Validação e Testes no Dispositivo

1. **Capturar Diagnóstico de GPU**:
   ```bash
   adb logcat -s DownpourGpuCaps DownpourDriver DownpourMain
   ```
2. **Verificar Flags Ativas**:
   - Confirmar no log que `textureCompressionBC` está ativo (1) com o driver Turnip.
   - Confirmar ausência de mensagens de parada de fila `vkQueueWaitIdle`.
3. **Observar Estabilidade Térmica e Texturas**:
   - Jogar por 15 a 20 minutos testando transições de cenário (ex: entrada em edifícios / Outro Mundo).
   - Verificar se as texturas brancas deixaram de ocorrer e se o frametime se manteve estável sem stutters de compilação.
