flowchart TB
    TITLE["Comparação arquitetural: Xbox 360 Xenos/eDRAM versus Adreno/GMEM/Tiling"]

    subgraph XBOX["Xbox 360 — Xenos + eDRAM física"]
        direction TB
        XCPU["CPU Xenon + memória guest\n512 MiB GDDR3 UMA"]
        XPM4["Ringbuffer / Command Processor\nPM4 e registradores Xenos"]
        XSTATE["Estado gráfico Xenos\nVS, PS, texturas, samplers, blend, depth, MSAA"]
        XFETCH["Fetch de vértices e texturas\nGDDR3 / memória compartilhada"]
        XSHADER["Shaders unificados\n3 arrays SIMD • 48 ALUs FP32\nvertex + pixel dinamicamente"]
        XRAST["Rasterização\ntriângulos • interpolação • early hierarchical-Z\nblocos de rasterização"]
        XBACK["Backend de saída\ncolor • Z/stencil • blending • alpha-to-mask\nMSAA 1x / 2x / 4x"]
        XEDRAM["eDRAM física — 10 MiB\nmemória local de framebuffer\n≈256 GB/s no caminho ROP–eDRAM\ncapacidade fixa"]
        XTILE["Predicated tiling / particionamento\nquando color + depth + MSAA excedem eDRAM"]
        XRESOLVE["Resolve por tile ou frame\nMSAA → superfície single-sample\nformato Xenos → memória observável"]
        XOUT["Resultado na GDDR3\nback buffer • textura resolvida\nreadback / apresentação"]

        XCPU --> XPM4 --> XSTATE
        XSTATE --> XFETCH
        XSTATE --> XSHADER
        XFETCH --> XSHADER
        XSHADER --> XRAST --> XBACK
        XBACK <--> XEDRAM
        XEDRAM -->|cabe no local| XRESOLVE
        XEDRAM -->|overflow de attachments| XTILE
        XTILE --> XBACK
        XRESOLVE --> XOUT
        XFETCH <--> XCPU
        XOUT <--> XCPU
    end

    subgraph ADRENO["Adreno — Vulkan + GMEM/Tiling gerenciado pelo driver"]
        direction TB
        ACPU["CPU / emulador / aplicação\nmemória UMA do SoC"]
        APARSER["Parser e estado sombra\nPM4 traduzido • dirty bits • estado canônico"]
        AIR["IR de recursos e shaders\nnormalização de formatos\nswizzle / endianess / semântica"]
        ASPV["SPIR-V validado\nVS + FS + compute\nperfil de capabilities Vulkan"]
        AVK["Command buffers Vulkan\ndescritores • pipelines • barriers\nrender pass / dynamic rendering"]
        ABIN["Binning / visibility\nparticionamento de primitivas por tile\nimplementação do driver"]
        ARAST["Renderização tiled\nrasterização • early-Z/LRZ\nfragmentos e blending"]
        AGM["GMEM / on-chip\nlayout e tamanho implementation-dependent\nnão é VkMemoryHeap diretamente alocável"]
        AATT["Attachments locais\ncolor • depth/stencil • MSAA\nload/store • transient attachments"]
        AUBWC["UBWC / compressão de superfície\nreduz tráfego para memória do sistema\nnão é ASTC nem BCn"]
        AUMA["Memória UMA do SoC\nLPDDR compartilhada\ntexturas • vértices • buffers • imagens"]
        ASTORE["Store / resolve quando necessário\nGMEM → memória UMA\nVkImage optimal / staging"]
        AOUT["Imagem final / textura amostrada\nreadback ou apresentação"]

        ACPU --> APARSER --> AIR --> ASPV --> AVK
        AVK --> ABIN --> ARAST
        ARAST <--> AGM
        AGM <--> AATT
        AATT -->|store/load ops e resolve| ASTORE
        AGM -.->|driver pode usar| AUBWC
        ASTORE --> AUMA --> AOUT
        AUMA -->|fetch de texturas e vértices| AVK
        ACPU <--> AUMA
    end

    subgraph TRANSLATION["Camada de tradução Xenos → Vulkan"]
        direction LR
        TSTATE["Mapeamento de estado\nPM4 → register file virtual"]
        TEDRAM["eDRAM virtual\nownership • alias • sample count\nresolve observável"]
        TFORMAT["Conversão de formatos\nXenos/BCn → VkFormat/ASTC\npack/unpack quando necessário"]
        TSYNC["Rastreador de hazards\nbarriers por recurso\nqueries • predicação • fences"]
        TCACHE["Caches\ntradução • SPIR-V • VkPipelineCache"]
        TSTATE --> TEDRAM --> TFORMAT --> TSYNC --> TCACHE
    end

    XSTATE -."estado guest".-> TSTATE
    XEDRAM -."modelo virtual; não é eDRAM real".-> TEDRAM
    XRESOLVE -."resolve pode disparar conversão".-> TFORMAT
    TFORMAT -."imagem/attachment Vulkan".-> AVK
    TSYNC -."barriers e observabilidade".-> AVK
    TCACHE -."pipelines reutilizáveis".-> AVK

    subgraph DIFFERENCES["Diferenças essenciais"]
        direction TB
        D1["Memória local\nXenos: eDRAM física de 10 MiB\nAdreno: GMEM interno gerenciado pelo driver"]
        D2["Tiling\nXenos: particionamento por capacidade fixa e resolve\nAdreno: binning + renderização tiled por implementação"]
        D3["Controle\nXenos: comportamento determinado pelo console/XDK\nAdreno: comportamento depende de SoC, firmware e driver"]
        D4["Observabilidade\nXenos: resolve, alias e registradores podem ser guest-visible\nVulkan: layouts, acessos e sincronização explícitos"]
        D5["Otimização\nXenos: reduzir overflow de eDRAM\nAdreno: manter attachments em GMEM e evitar stores/resolves"]
        D6["Equivalência proibida\neDRAM ≠ GMEM ≠ VkMemoryHeap\nUBWC ≠ ASTC ≠ BCn"]
    end

    XEDRAM --> D1
    AGM --> D1
    XTILE --> D2
    ABIN --> D2
    XSTATE --> D3
    APARSER --> D3
    XRESOLVE --> D4
    TSYNC --> D4
    XBACK --> D5
    AATT --> D5
    D1 --> D6
    D2 --> D6

    classDef xbox fill:#173f5f,stroke:#0b2538,color:#ffffff,stroke-width:2px;
    classDef adreno fill:#0b6e4f,stroke:#064632,color:#ffffff,stroke-width:2px;
    classDef trans fill:#7a4eab,stroke:#4b2c6f,color:#ffffff,stroke-width:2px;
    classDef diff fill:#8a5a00,stroke:#583a00,color:#ffffff,stroke-width:2px;
    classDef title fill:#222222,stroke:#111111,color:#ffffff,stroke-width:3px;

    class XCPU,XPM4,XSTATE,XFETCH,XSHADER,XRAST,XBACK,XEDRAM,XTILE,XRESOLVE,XOUT xbox;
    class ACPU,APARSER,AIR,ASPV,AVK,ABIN,ARAST,AGM,AATT,AUBWC,AUMA,ASTORE,AOUT adreno;
    class TSTATE,TEDRAM,TFORMAT,TSYNC,TCACHE trans;
    class D1,D2,D3,D4,D5,D6 diff;
    class TITLE title;
