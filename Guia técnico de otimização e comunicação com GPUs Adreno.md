# Guia técnico de otimização e comunicação com GPUs Adreno

## Objetivo

Este documento apresenta uma estratégia prática para melhorar o desempenho e a comunicação entre CPU, Vulkan, drivers e GPUs Qualcomm Adreno. O foco principal é uma aplicação que traduz funções gráficas de uma GPU Xenos do Xbox 360 para Vulkan, mas os princípios também se aplicam a jogos e renderizadores nativos para Android.

A premissa fundamental é que uma Adreno não pode ser transformada fisicamente em uma Xenos por software. O ganho deve vir da redução do custo de tradução, da diminuição de cópias e sincronizações, da compilação eficiente de shaders e do uso correto da arquitetura tiled da Adreno.

## 1. Princípios prioritários

A ordem recomendada de otimização é:

| Prioridade | Medida | Impacto esperado | Risco |
|---|---|---:|---:|
| 1 | Evitar recriação de pipelines | Muito alto | Baixo |
| 2 | Reduzir mudanças de estado | Alto | Baixo |
| 3 | Reduzir cópias, resolves e barreiras globais | Alto | Médio |
| 4 | Aproveitar render passes, subpasses e GMEM | Alto | Médio |
| 5 | Melhorar a tradução de shaders | Muito alto | Médio |
| 6 | Reduzir draw calls e submissões | Alto | Médio |
| 7 | Usar FP16 seletivamente | Médio ou alto | Alto |
| 8 | Usar descriptor indexing e recursos avançados | Variável | Alto |
| 9 | Usar extensões proprietárias | Variável | Alto |

A regra geral é implementar primeiro uma versão correta e mensurável. Depois, cada otimização deve ser ativada individualmente e comparada com a versão de referência.

## 2. Modelo de comunicação CPU–GPU

A comunicação deve ser tratada como um pipeline:

```text
CPU ou emulador
    ↓
Parser de comandos Xenos/PM4
    ↓
Estado sombra e dirty bits
    ↓
Estado gráfico canônico
    ↓
Tradutor de shaders e recursos
    ↓
Gravação de command buffers Vulkan
    ↓
Driver Qualcomm/Turnip
    ↓
GPU Adreno
```

O objetivo é impedir que cada alteração de estado produza imediatamente uma chamada Vulkan. O parser deve acumular as alterações e materializar o estado somente quando houver um evento que realmente exija execução, como um draw, dispatch, resolve ou query.

## 3. Estado sombra e dirty bits

Um erro de implementação é converter cada registro Xenos diretamente em um bind Vulkan. Esse modelo produz excesso de chamadas, validações e alterações de pipeline.

O modelo recomendado é:

```text
registro Xenos recebido
    ↓
atualiza register file virtual
    ↓
marca o grupo como dirty
    ↓
continua processando comandos
    ↓
no draw: materializa somente o que mudou
```

Separe os estados em grupos independentes:

- Vertex shader.
- Fragment shader.
- Constantes.
- Texturas.
- Samplers.
- Vertex buffers.
- Index buffers.
- Blend.
- Depth e stencil.
- Rasterização.
- Viewport e scissor.
- Render targets.
- Predicação.
- Queries.
- Resolve e sincronização.

Uma alteração no viewport não deve recriar shaders, descritores ou recursos que permaneceram iguais.

### Exemplo de estado sombra

```cpp
struct ShadowState {
    bool shader_dirty;
    bool constants_dirty;
    bool textures_dirty;
    bool samplers_dirty;
    bool vertex_input_dirty;
    bool raster_dirty;
    bool depth_stencil_dirty;
    bool blend_dirty;
    bool render_targets_dirty;
    bool synchronization_dirty;
};
```

No ponto de execução do draw, o backend consulta esses flags, atualiza somente os objetos necessários e limpa apenas os flags consumidos.

## 4. Redução de draw calls

Draw calls são caras porque podem envolver atualização de estado, validação de descritores, mudanças de pipeline e gravação de comandos.

### Em um port específico

Em um port adaptado para um jogo determinado, é possível agrupar draws que compartilhem:

- Pipeline.
- Material.
- Texturas.
- Render targets.
- Topologia.
- Estado de depth e blend.
- Dados de instância.

Também podem ser usados:

- `vkCmdDrawIndirect`.
- `vkCmdDrawIndexedIndirect`.
- `VK_KHR_draw_indirect_count`, se disponível.
- Instancing.
- Culling em compute.
- Agrupamento por material.

### Emulação fiel

Na emulação genérica, o agrupamento deve ser conservador. Não agrupe draws se houver alteração observável em:

- Predicação.
- Query de oclusão.
- Memexport.
- Resolve.
- Alias de memória.
- Leitura pela CPU.
- Render target.
- Ordem dos comandos.
- `discard` ou `kill`.

Compatibilidade deve ter prioridade sobre a redução artificial de draw calls.

## 5. Submissão e sincronização

Evite submissões fragmentadas como:

```text
vkQueueSubmit
vkQueueWaitIdle
vkQueueSubmit
vkQueueWaitIdle
```

Esse padrão paralisa a CPU e a GPU. Prefira acumular comandos por frame ou por lote lógico e fazer poucas submissões.

### Uso adequado das primitivas Vulkan

| Primitiva | Função principal |
|---|---|
| Fence | Informar à CPU que uma submissão terminou |
| Semaphore | Coordenar filas ou submissões diferentes |
| Pipeline barrier | Ordenar e tornar visível o acesso a um recurso |
| Event | Sincronização específica dentro do fluxo |
| Query | Medir ou obter um resultado produzido pela GPU |

`vkQueueWaitIdle()` deve ser reservado para situações excepcionais, como encerramento, recuperação ou depuração. Ele não deve ser usado em cada resolve, draw ou frame.

### Barriers mínimas

A mesma fila não elimina a necessidade de visibilidade de memória, mas uma barreira global em todos os comandos também prejudica o desempenho.

Evite:

```text
draw
→ barrier ALL_COMMANDS
→ draw
→ barrier ALL_COMMANDS
```

Prefira rastrear cada recurso:

```text
Imagem A:
    COLOR_ATTACHMENT_WRITE
    → FRAGMENT_SHADER_READ

Buffer B:
    COMPUTE_SHADER_WRITE
    → INDIRECT_COMMAND_READ

Query C:
    QUERY_WRITE
    → HOST_READ
```

Use `vkCmdPipelineBarrier2` ou `VK_KHR_synchronization2` quando disponível, sempre especificando os estágios e acessos mínimos necessários.

## 6. Buffers persistentes e ring buffers

Para constantes, vértices, índices e dados temporários, use buffers grandes e subalocados por frame. Evite alocar memória Vulkan para cada draw.

Modelo recomendado:

```text
Frame 0 → região A
Frame 1 → região B
Frame 2 → região C

quando a GPU termina o Frame 0:
    região A pode ser reutilizada
```

Boas práticas:

- Usar allocators lineares por frame.
- Manter buffers mapeados quando apropriado.
- Reutilizar buffers e descritores.
- Controlar a reciclagem com fences por frame.
- Respeitar `nonCoherentAtomSize` em memória não coerente.
- Evitar `vkAllocateMemory` dentro do caminho de renderização.

## 7. Aproveitamento da arquitetura tiled da Adreno

A Adreno utiliza renderização baseada em tiles e memória interna semelhante a GMEM. A aplicação deve permitir que o driver mantenha color, depth, stencil e intermediários próximos ao rasterizador.

### Render passes e subpasses

Mantenha uma cadeia coerente dentro do mesmo render pass quando isso for compatível com a semântica do jogo:

```text
G-buffer
    ↓
iluminação
    ↓
transparência
    ↓
pós-processamento
```

Encerrar e reabrir render passes pode obrigar o driver a salvar e recarregar attachments na memória externa.

### Load e store operations

Use `LOAD_OP_CLEAR` quando o conteúdo anterior não for necessário. Use `LOAD_OP_DONT_CARE` quando o conteúdo anterior for irrelevante. Use `STORE_OP_DONT_CARE` quando o resultado não for consumido posteriormente.

Exemplo conceitual:

```text
attachment intermediário:
    load  = CLEAR
    store = DONT_CARE

imagem final:
    load  = DONT_CARE
    store = STORE
```

A escolha correta pode reduzir o tráfego entre GMEM e a memória compartilhada do sistema.

### Evitar resolves desnecessários

Não materialize um attachment multisample ou local antes de ele se tornar observável.

Modelo ruim:

```text
render
→ resolve
→ copiar
→ render novamente
→ resolve
```

Modelo preferível:

```text
render
→ continuar no tile
→ resolve somente quando necessário
```

Na tradução Xenos, o resolve deve ocorrer quando o comando guest o exige ou quando outra operação precisa observar o conteúdo. Não antecipe o resolve apenas por conveniência do backend.

## 8. Texturas, formatos e memória

### Conversão offline

Converter texturas em tempo de execução pode consumir CPU, GPU e memória. Para um port, prefira:

```text
textura original
→ conversão offline
→ geração dos mipmaps
→ compressão ASTC ou ETC2
→ armazenamento no pacote
```

Quando necessário, mantenha variantes por perfil:

```text
variante ASTC
variante ETC2
variante não comprimida
variante de fallback
```

### Diferenças entre compressões

Não confunda:

- BCn/DXT: compressão de texels.
- ETC2/ASTC: compressão de texels voltada a dispositivos móveis.
- UBWC: compressão e organização proprietária de superfícies para reduzir largura de banda.
- GMEM: memória local usada durante a renderização tiled.

UBWC não é um formato portátil de textura e não deve ser tratado como equivalente a BCn ou ASTC.

### Staging para imagens

O caminho geral é:

```text
CPU
→ staging buffer
→ cópia para VkImage optimal
→ transição de layout
→ uso como textura
```

Imagens lineares são úteis em casos específicos, mas não devem ser usadas como solução geral para texturas de GPU. O tiling optimal deve permanecer sob controle do driver.

### Espaço de cor

Use variantes `_SRGB` somente para dados realmente codificados em sRGB. Use formatos lineares para:

- Normal maps.
- Roughness.
- Metallic.
- Depth.
- IDs.
- Máscaras.
- Buffers intermediários.

Uma textura que já está linearizada não deve receber uma segunda conversão de sRGB.

## 9. Otimização de shaders

### Primeira versão: fidelidade

A primeira implementação deve priorizar correção:

- FP32.
- Saturação correta.
- Operações de controle corretas.
- Tratamento adequado de `NaN` e `INF`.
- Preservação de `discard` e predicação.
- Comportamento correto de acesso fora dos limites.

Depois, crie variantes separadas:

```text
shader_fp32_fiel
shader_relaxed
shader_fp16
```

Essas variantes não devem compartilhar o mesmo binário no cache.

### Uso seletivo de FP16

FP16 pode reduzir registradores, largura de banda e custo aritmético em determinadas gerações Adreno. Pode ser apropriado para:

- Cor.
- Parâmetros de materiais.
- Roughness.
- Metallic.
- Partículas.
- Pós-processamento tolerante a erro.

Mantenha FP32 em:

- Posição.
- Profundidade.
- Índices.
- Coordenadas sensíveis de textura.
- Decisões de controle.
- Acumulações longas.
- Cálculos que influenciam predicação.

A redução de precisão deve ser validada com imagens e testes numéricos.

### Reduzir pressão de registradores

Evite:

- Unroll indiscriminado.
- Arrays dinâmicos grandes.
- Loops com controle excessivo.
- Variáveis temporárias desnecessárias.
- Muitas texturas simultâneas.
- Código duplicado por especializações mal planejadas.

Um shader menor e com melhor ocupação pode ser mais rápido que um shader que executa menos instruções, mas consome registros demais.

## 10. Fluxo de controle traduzido

Um fallback comum para controle Xenos não estruturado é um loop com program counter:

```cpp
while (running) {
    switch (program_counter) {
        case 0:
            execute_block_0();
            break;
        case 1:
            execute_block_1();
            break;
    }
}
```

Esse método pode aumentar instruções, registradores e pressão no cache. Sempre que possível, converta o fluxo para:

- CFG estruturado.
- `if/else`.
- Loops Vulkan/SPIR-V estruturados.
- Predicação.
- Especialização estática.

Use o program counter somente como caminho de fallback.

## 11. Descritores e recursos

Evite criar um novo `VkDescriptorSetLayout` para cada draw. Uma organização possível é:

```text
Set 0: recursos globais por frame
Set 1: material
Set 2: objeto
Set 3: texturas e samplers
```

Atualize somente os recursos que mudaram. Mantenha flags separados para constantes, texturas, samplers e buffers.

Descriptor indexing ou bindless pode reduzir binds, mas é um recurso opcional. Deve ser ativado somente depois de consultar:

- Features Vulkan.
- Limites de descritores.
- Suporte a indexação não uniforme.
- Limites de arrays.
- Comportamento do driver.

Para Adreno 619, mantenha um fallback com arrays fixos ou descritores por material. O caminho bindless deve ser adicional, não obrigatório.

## 12. Cache em camadas

Use três níveis de cache:

```text
microcódigo Xenos
    ↓
IR traduzida
    ↓
SPIR-V
    ↓
VkPipeline
```

A chave deve conter:

- Hash do shader guest.
- Estágio do shader.
- Versão da IR.
- Opções de precisão.
- Layout de descritores.
- Formatos de render target.
- Sample count.
- Estado de blend e depth.
- Versão do tradutor.
- Perfil de capacidades Vulkan.

O `VkPipelineCache` deve ser associado a:

- `vendorID`.
- `deviceID`.
- Versão do driver.
- `pipelineCacheUUID`.
- Versão do backend.

Não compartilhe cegamente um cache entre Adreno 619, 7xx, 8xx ou versões diferentes de driver.

## 13. Detecção real do dispositivo

O nome “Adreno” não é um contrato Vulkan único. O backend deve consultar em runtime:

```text
apiVersion
vendorID
deviceID
driverID
driverVersion
extensões
features
limites
formatos
subgroup properties
memória disponível
```

Evite lógica baseada somente no nome da GPU:

```cpp
if (gpu_name == "Adreno") {
    enable_everything();
}
```

Prefira capacidades reais:

```cpp
if (features.dynamicRendering &&
    features.synchronization2 &&
    format_supports_render_target &&
    limits.maxColorAttachments >= required_attachments) {
    enable_fast_path();
} else {
    enable_fallback_path();
}
```

Perfis recomendados:

| Perfil | Estratégia |
|---|---|
| Adreno 619 | Vulkan baseline, descritores fixos, FP32 inicial e poucos recursos opcionais |
| Adreno 7xx | Recursos Vulkan modernos quando confirmados pelo driver |
| Adreno 8xx | Recursos avançados somente após verificação específica do driver |
| Driver desconhecido | Caminho conservador, com fallbacks e validação intensiva |

## 14. Medição de desempenho

FPS isolado não é suficiente. Registre:

- Tempo da CPU no parser.
- Tempo de gravação de command buffers.
- Tempo de criação de pipeline.
- Tempo de GPU.
- Número de draw calls.
- Número de barriers.
- Número e tamanho dos resolves.
- Fragment invocations.
- Uso de memória.
- Stalls de cache.
- Pressão de registradores.
- Temperatura.
- Frequência sustentada.
- Tempo do primeiro frame.
- Tempo com cache frio.
- Tempo com cache aquecido.

Classifique o gargalo antes de otimizar:

```text
CPU limitada
GPU limitada
memória limitada
driver limitada
termicamente limitada
```

Uma redução de draw calls não resolve um shader limitado por largura de banda. Uma redução de precisão não resolve uma aplicação bloqueada em `vkQueueWaitIdle()`.

## 15. Plano de implementação recomendado

### Fase 1 — Referência correta

- Parser PM4 validado.
- Register file virtual.
- Dirty bits.
- FP32.
- Descritores fixos.
- Barriers conservadoras.
- Traces reproduzíveis.
- Comparação de imagens e resultados.

### Fase 2 — Memória e formatos

- Modelo de buffers e imagens.
- EDRAM virtual.
- Color/depth/stencil.
- Resolve.
- Mipmaps.
- BCn, ETC2 e ASTC quando suportados.
- Pack/unpack para formatos sem equivalente direto.

### Fase 3 — Cache e submissão

- Cache de tradução.
- Cache de SPIR-V.
- Cache de pipelines.
- Allocators lineares.
- Buffers persistentes.
- Descritores reutilizáveis.
- Poucas submissões por frame.

### Fase 4 — Caminhos rápidos

- Render passes e subpasses.
- Attachments transitórios.
- Batching seguro.
- Draws indiretos.
- Culling em compute.
- FP16 seletivo.
- Descriptor indexing opcional.

### Fase 5 — Perfis por dispositivo

- Adreno 619.
- Adreno 7xx.
- Adreno 8xx.
- Drivers Qualcomm proprietários.
- Turnip/Freedreno quando aplicável.
- Fallbacks para extensões ausentes.

Cada fase deve ser comparada com a referência anterior. Uma otimização só deve ser mantida se melhorar o tempo medido sem introduzir divergência visual ou semântica.

## 16. Checklist final

### Comunicação

- [ ] O parser não emite Vulkan para cada registro.
- [ ] O estado sombra usa dirty bits.
- [ ] A CPU não aguarda a GPU em cada evento.
- [ ] Buffers temporários usam ring buffers.
- [ ] Recursos são atualizados somente quando mudam.

### GPU

- [ ] Shaders possuem cache.
- [ ] Pipelines possuem cache.
- [ ] FP16 foi aplicado somente onde validado.
- [ ] Unroll e arrays dinâmicos foram medidos.
- [ ] Draw calls foram reduzidas sem quebrar a ordem guest.

### Memória

- [ ] Resolve ocorre somente quando necessário.
- [ ] Load/store operations estão corretos.
- [ ] GMEM pode ser usado pelo driver.
- [ ] Texturas são convertidas offline quando possível.
- [ ] Imagens optimal são usadas para recursos de GPU.

### Vulkan

- [ ] Features são consultadas em runtime.
- [ ] Limites do dispositivo não estão codificados.
- [ ] Barriers são específicas por recurso.
- [ ] Descriptor indexing tem fallback.
- [ ] O pipeline cache é invalidado quando o driver muda.

## Conclusão

Os maiores ganhos para GPUs Adreno vêm de reduzir trabalho repetido, manter dados no caminho tiled local, evitar cópias e resolves, controlar o custo dos shaders e impedir que a CPU bloqueie a GPU.

Para um tradutor Xenos–Vulkan, a estratégia correta é implementar primeiro uma referência fiel e lenta. Depois, devem ser adicionados caches, batching seguro, render passes, draws indiretos, FP16 seletivo e recursos específicos da Adreno.

A regra central é:

> **Primeiro reproduzir corretamente a semântica da Xenos; depois otimizar o caminho Vulkan específico da Adreno.**

O backend não deve assumir que todas as GPUs Adreno têm as mesmas capacidades. O dispositivo, o firmware e o driver devem ser consultados em runtime, e cada otimização deve possuir um fallback funcional.

## Referências

[1]: https://docs.qualcomm.com/bundle/publicresource/topics/80-78185-2/overview.html "Qualcomm Adreno GPU Overview"
[2]: https://docs.qualcomm.com/bundle/publicresource/topics/80-78185-2/mobile_best_practices.html "Qualcomm Adreno GPU Mobile Best Practices"
[3]: https://docs.vulkan.org/spec/latest/chapters/synchronization.html "Khronos Vulkan Specification: Synchronization and Cache Control"
[4]: https://docs.vulkan.org/guide/latest/tile_based_rendering_best_practices.html "Vulkan Guide: Tile Based Rendering Best Practices"
[5]: https://docs.vulkan.org/spec/latest/chapters/descriptorsets.html "Khronos Vulkan Specification: Descriptor Sets"
[6]: https://docs.vulkan.org/spec/latest/chapters/pipelines.html "Khronos Vulkan Specification: Pipelines and Pipeline Cache"
[7]: https://docs.vulkan.org/spec/latest/chapters/formats.html "Khronos Vulkan Specification: Formats"
[8]: https://docs.vulkan.org/spec/latest/chapters/features.html "Khronos Vulkan Specification: Features"
[9]: https://docs.vulkan.org/spec/latest/chapters/limits.html "Khronos Vulkan Specification: Limits"
[10]: https://registry.khronos.org/vulkan/specs/latest/man/html/VK_KHR_draw_indirect_count.html "Vulkan VK_KHR_draw_indirect_count Reference"
[11]: https://github.com/hedge-dev/XenosRecomp "XenosRecomp: Xbox 360 Renderer Translation Project"
[12]: https://github.com/xenia-project/xenia/blob/master/docs/gpu.md "Xenia GPU Documentation"
[13]: https://github.com/xenia-project/xenia/blob/master/src/xenia/gpu/xenos.h "Xenia Xenos GPU Definitions"
