# Inferência local: inventário e plano

[Índice](../README.md) · [Arquitetura](architecture.md) · [Requisitos](requirements.md) · [Segurança](security-and-identity.md).

Inspeção realizada em **12/11/2026**, com comandos somente de leitura. Valores de memória/uso representam instantes distintos, não benchmark. Não houve inferência, pull, atualização, reinício, conexão de redes, alteração de volumes ou arquivos nos projetos consultados.

## Informações confirmadas pelo usuário

No complemento de planejamento, o usuário confirmou **RTX 5060 Ti com 16 GB de VRAM, Ryzen 9 5950X, aproximadamente 64 GiB de RAM, Ollama 0.18.0 executado pela infraestrutura `divine-touch-infra` e os modelos instalados `qwen3:14b`, `gpt-oss:20b` e `divine-touch-30b:latest`**. A referência local é `D:/projects/divine-touch/divine-touch-infra`.

Essas confirmações correspondem à inspeção anterior; esta atualização não repetiu consultas de runtime. Arquitetura, quantização, template, contexto e capacidade do modelo customizado não são inferidos pelo nome nem confirmados automaticamente por essa lista. Metadados técnicos apresentados adiante são observações da consulta anterior a `/api/show`, com proveniência distinta, e não prova de qualidade ou capacidade efetiva.

**C:** usar esse Ollama como padrão de desenvolvimento e validação para evitar cobrança de inferência externa nas iterações. Fazer um teste curto com o que já existe, mas atualizar o runtime quando necessário e considerar modelo mais atual/apropriado à GPU, conforme a orientação mais recente do usuário. Não há atualização automática pela aplicação; manutenção e downloads serão passos explícitos do exercício. A demonstração independente do desktop usará VPS com API externa, conforme [cloud-demo-and-portfolio.md](cloud-demo-and-portfolio.md). O Java permanece harness e o gateway continua governando ferramentas.

## Fontes e método

Fontes locais lidas seletivamente, sem arquivos de segredos:

- `D:/projects/iam-trainer/iam-trainer-core/docker-compose.yml` e `README.md`.
- `D:/projects/iam-trainer/system-design/readme.md` e `project-plan.md`.
- `D:/projects/divine-touch/divine-touch-infra/docker-compose.yml` e `README.md`.

Diagnóstico: `nvidia-smi` no host e dentro do container; consultas CIM de CPU/RAM; `docker ps`/`ps -a`; `ollama --version`, `list` e `ps`; campos específicos de imagem, health, volumes, rede, reserva de GPU e labels Compose; `/api/version` e `/api/show` com seleção de metadados. `/api/show` é consulta de metadados via POST, sem executar geração. Apenas variáveis Ollama/NVIDIA não sensíveis previamente selecionadas foram exibidas; não foi impressa inspeção completa nem conteúdo de `.env`.

As consultas Docker/CIM inicialmente bloqueadas pelo sandbox foram repetidas com a aprovação solicitada pela ferramenta. As verificações concluídas abaixo não são inferências baseadas apenas em README.

## Hardware

| Item | Evidência em execução | Informação documentada / limite |
| --- | --- | --- |
| GPU | NVIDIA GeForce RTX 5060 Ti; 16.311 MiB totais reportados | Corresponde à classe 16 GB citada nos projetos |
| Driver host | 576.88 | Versão observada; não é recomendação de versão atual |
| Uso instantâneo | Cerca de 1.787–1.832 MiB usados, 1–2% de utilização | Sem geração ativa observada; não prova uso de GPU pelo modelo |
| CPU | AMD Ryzen 9 5950X; 16 cores e 32 processadores lógicos | Compatível com o hardware citado em system-design |
| RAM física host | 68.635.979.776 bytes, aproximadamente 63,9 GiB | Não mede memória disponível ao Docker/WSL2 |
| Disco | Não medido | system-design menciona SSD de 1 TB+; espaço livre e capacidade efetiva permanecem pendentes |
| Limites Docker/WSL2 | Não medidos | Medir RAM/CPU disponíveis à VM antes de benchmark de capacidade |

A [matriz oficial de hardware do Ollama](https://docs.ollama.com/gpu) inclui a RTX 5060 Ti/arquitetura 12.0. Isso apoia a candidatura da GPU; a combinação concreta de imagem, driver e modelo ainda precisa de uma inferência observada. A [documentação Docker](https://docs.ollama.com/docker) descreve o acesso NVIDIA ao container e a persistência em `/root/.ollama`.

## Qual configuração está ativa

| Item | `iam-trainer-core` documentado | `divine-touch-infra` documentado e execução |
| --- | --- | --- |
| Container | `iam-trainer-ollama` | **Ativo:** `divine-touch-ollama` |
| Imagem declarada | `ollama/ollama:latest` | `ollama/ollama:latest` |
| Ativação | Profile `llm` | Serviço sem profile obrigatório no Compose consultado |
| GPU | Reserva NVIDIA, count all, capability gpu | Mesma reserva, confirmada por DeviceRequests; GPU visível no container |
| Volume de modelos | `ollama-data:/root/.ollama` | **Volume efetivo:** `divine-touch-infra_ollama_data:/root/.ollama` |
| Bind adicional | Não observado na seção Ollama | `D:/projects/divine-touch/divine-touch-infra/models` → `/models` |
| Porta | `${OLLAMA_HOST_PORT:-11434}:11434` | `11434:11434`; runtime publica em `0.0.0.0` e `[::]` |
| Rede | Chave Compose `iam-trainer-net` | **Rede efetiva:** `divine-touch-infra_divine-touch-net`; aliases `ollama` e `divine-touch-ollama` |
| Paralelismo | Default Compose 4, ajustável por variável | Runtime `OLLAMA_NUM_PARALLEL=1` |
| Keep alive | Default Compose 15m | Runtime `OLLAMA_KEEP_ALIVE=30m` |
| Outros controles | Defaults: max loaded 1, queue 128, flash attention 1 | Runtime flash attention 1, `OLLAMA_NUM_CTX=8192`; max loaded/queue não definidos nas variáveis selecionadas |
| Health check | `ollama list` | `ollama list`; container estava healthy |

Labels `com.docker.compose.project.working_dir` e `project.config_files` apontam explicitamente para **`D:/projects/divine-touch/divine-touch-infra/docker-compose.yml`**. Não é apenas semelhança de nome: labels, mounts, rede e parâmetros confirmam a origem. O container `iam-trainer-ollama` foi encontrado parado; não foi iniciado.

Também estavam ativos `divine-touch-postgres` (`postgres:15`, porta 5432) e `divine-touch-pgadmin` (`dpage/pgadmin4`, porta 5050). O Compose `iam-trainer` descreve treinamento PyTorch/CUDA, PostgreSQL/pgvector, Redis, Keycloak, aplicações e observabilidade; os containers encontrados desse projeto estavam parados. Isso não prova ausência de clientes fora do Docker ou uso futuro da mesma GPU. Não reutilizar o banco ou os volumes de aplicações alheias implicitamente.

## Versão e modelos efetivos

Versão do CLI e da API: **Ollama 0.18.0**. Estado observado: running/healthy.

Imagem/digest de repositório observado: `ollama/ollama@sha256:e23e6499890325d31f3454cdc83f7a613a2423c8a9e167bbafea0b0e3c27d7c6`. A tag `latest` no Compose não comprova atualização; o digest e a versão executada identificam o snapshot encontrado.

| Modelo instalado | ID local abreviado | Tamanho em `ollama list` | Metadados de `/api/show` | Contexto nominal do artefato |
| --- | --- | --- | --- | --- |
| `qwen3:14b` | `bdbd181c33f2` | 9,3 GB | qwen3, 14,8B, Q4_K_M; completion/tools/thinking | 40.960 tokens |
| `gpt-oss:20b` | `17052f91a42e` | 13 GB | gptoss, 20,9B, MXFP4; completion/tools/thinking | 131.072 tokens |
| `divine-touch-30b:latest` | `93c850ab1d3a` | 13 GB | qwen3moe, 30,5B, Q3_K_M; completion/tools; parâmetro próprio `num_ctx=8192` | 262.144 tokens |

Parâmetros numéricos encontrados: qwen3 temperature 0,6/top_k 20/top_p 0,95/repeat_penalty 1; gpt-oss temperature 1; customizado temperature 0,7/top_k 20/top_p 0,8/repeat_penalty 1,05. São metadados dos artefatos, não parâmetros recomendados para a review.

`ollama ps` retornou lista vazia. Logo, **nenhum contexto alocado, percentual de offload, tokens/s ou inferência GPU foi comprovado**. Reserva NVIDIA e `nvidia-smi` no container comprovam acesso ao dispositivo, não execução do modelo nele. Não executar geração apenas para completar esse inventário.

Os modelos `mistral:7b`, `llama3.2:3b`, `nomic-embed-text` e artefatos de fine-tuning mencionados no `iam-trainer` são configurações/documentação desse projeto; não foram encontrados no Ollama ativo. O modelo customizado encontrado não será adotado apenas pelo nome/tamanho; proveniência, template e adequação à tarefa precisam de revisão.

## Contexto: divergência a validar

O Compose e o ambiente ativo contêm **`OLLAMA_NUM_CTX=8192`**. A documentação oficial de [contexto](https://docs.ollama.com/context-length) e a [FAQ](https://docs.ollama.com/faq) documentam `OLLAMA_CONTEXT_LENGTH` no serviço e `options.num_ctx` por chamada. Não foi comprovado que `OLLAMA_NUM_CTX` altere o default do servidor 0.18.0. Não afirmar que o serviço já oferece 8K efetivos ao agente.

O parâmetro `num_ctx=8192` do modelo customizado é uma configuração de artefato diferente da variável de ambiente. Os modelos nativos consultados não exibiram esse parâmetro. Contexto nominal, parâmetro solicitado e janela efetivamente alocada são três medidas distintas.

**P:** configurar contexto explicitamente na integração/chamada e observar `ollama ps` durante o benchmark. Começar com janela moderada, por exemplo 4K e depois 8K, se couber, sem alterar o serviço compartilhado nesta tarefa. Reservar tokens para instruções, ferramentas, histórico e resposta. Mais contexto/paralelismo consome mais memória; não inferir que os pesos de 9,3 GB garantem qualquer janela ou batch em 16 GB.

## Reaproveitamento e rede recomendados

**P recomendada para o primeiro acesso:** containers Java usam endpoint configurável **`http://host.docker.internal:11434`**, aproveitando a porta já publicada pelo serviço ativo. A [documentação Docker Desktop](https://docs.docker.com/desktop/features/networking/networking-how-tos/) descreve esse DNS para acesso ao host. A API foi consultada pelo host nesta inspeção; o caminho a partir de um futuro container deste projeto ainda não foi testado.

A recomendação evita acoplar inicialmente a aplicação à rede inteira do `divine-touch`, que também contém banco e pgAdmin, e não exige alterar o Compose consultado. Depende do Docker Desktop, do serviço proprietário estar ativo e de controles de exposição adequados. Não usar `localhost:11434` dentro do container Java: ele apontaria para o próprio container.

**Alternativa P:** declarar no futuro Compose do novo projeto a rede existente `divine-touch-infra_divine-touch-net` como externa e conectar apenas o componente que acessa o modelo; endpoint `http://divine-touch-ollama:11434`. Não criar uma segunda rede de mesmo nome lógico supondo conectividade automática; não fixar IP transitório. Essa alternativa amplia a confiança de rede e depende do ciclo de vida da rede externa. Uma rede dedicada de inferência poderia melhorar isolamento futuramente, mas exigiria alteração coordenada da infraestrutura e não foi criada.

Nenhuma das alternativas fornece autenticação por si só. A exposição observada em todas as interfaces exige confirmar firewall, alcance LAN e política antes de usar dados reais. Não criar outra instância Ollama ou baixar cópias dos modelos por conveniência.

## Integração e escolha experimental do modelo

**C:** aproveitar inferência local mantendo opção arquitetural de outros provedores; modelos instalados servem de baseline, sem obrigar avaliação exaustiva antes de experimentar outro mais adequado. **P:** Spring AI com integração nativa Ollama, endpoint/modelo configuráveis, pull automático desabilitado e limites simples por chamada/trabalho. A [integração oficial](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html) documenta essas capacidades; verificar o conjunto runtime/modelo no exercício.

| Candidato | Por que avaliar | Limite da recomendação |
| --- | --- | --- |
| `qwen3:14b` instalado | Menor tamanho entre os nativos já presentes; catálogo e metadados indicam ferramentas; ponto inicial P sem download | Validar qualidade de código, JSON Schema, tempo de raciocínio e margem de KV cache |
| `gpt-oss:20b` instalado | Comparação local disponível com tools/thinking | Pesos maiores reduzem margem; ID local difere do catálogo consultado; não substituir silenciosamente a tag |
| `divine-touch-30b:latest` instalado | Referência opcional de um artefato já existente | Quantização Q3 e template customizado exigem análise; não é default proposto |
| `qwen3.5:9b` não instalado | **Novo candidato recomendado para comparação curta:** família mais recente que qwen3, com tools/thinking e variante catalogada de cerca de 7,6 GB | Candidato para os 16 GB, sujeito ao contexto e memória de execução; conferir runtime/variante e testar JSON, ferramentas e review antes de adotar |
| `qwen2.5-coder:7b` não instalado | Alternativa menor e especializada em código, preservada como opção | Só experimentar se ajudar a resolver um problema; não ampliar a comparação sem necessidade |

Catálogos oficiais consultados: [qwen3:14b](https://ollama.com/library/qwen3:14b), [gpt-oss:20b](https://ollama.com/library/gpt-oss:20b), [qwen3.5:9b](https://ollama.com/library/qwen3.5:9b), [qwen2.5-coder:7b](https://ollama.com/library/qwen2.5-coder:7b). A indicação de `qwen3.5:9b` é uma proposta de experimento, não resultado medido. Tamanho de download não é consumo total de VRAM. O gpt-oss publicado atualmente tem outro ID/tamanho; manter o baseline identificado até uma mudança explícita.

Não começar simplesmente pelo maior modelo novo: o [qwen3-coder:30b](https://ollama.com/library/qwen3-coder:30b) catalogado ocupa cerca de 19 GB, antes de considerar contexto e execução. Por isso não é minha primeira sugestão para manter inferência na GPU de 16 GB; offload em RAM pode ser explorado depois, se houver interesse.

O [tool calling do Ollama](https://docs.ollama.com/capabilities/tool-calling) produz pedidos de ferramenta; o harness valida e executa via MCP, sem conceder acesso direto ao modelo. A [saída estruturada](https://docs.ollama.com/capabilities/structured-outputs) permite schema, mas a aplicação ainda deve validar conteúdo e evidência. **P:** usar rodadas de ferramentas separadas da resposta final com JSON Schema, testando ambas com o modelo/versão escolhidos antes de combiná-las.

Não escolher modelo por novidade, contagem de parâmetros ou contexto nominal. Classificar por achados válidos, falsos positivos, cobertura, memória e tempo. Nenhum modelo vencedor foi selecionado.

## Avaliação curta de aprendizado, ainda não executada

**P inicial:** quatro PRs/fixtures: um bug conhecido, uma alteração correta, um bug entre arquivos e uma tentativa de prompt injection. Incluir mais de 20 arquivos em um desses exemplos para mostrar que a enumeração não tem esse corte. Saber previamente o resultado esperado; usar dados demonstrativos.

1. Anotar runtime, modelo/variante, contexto, prompt e resultado esperado para conseguir repetir o exemplo.
2. Fazer uma chamada simples, observar GPU/VRAM e testar uma ferramenta e JSON de saída com validação no Java.
3. Rodar os mesmos quatro casos no baseline existente e no candidato escolhido. Uma rodada por modelo basta para começar; repetir se o resultado for duvidoso. Observar diferença frio/quente se aparecer, sem ensaio formal obrigatório.
4. Registrar achados corretos, falsos positivos, chamadas de ferramenta, validade da saída, tempo e cobertura. Começar com uma inferência simultânea; não fazer teste de carga como pré-requisito.

Só publicar achados que passem a validação; demonstrar bloqueio de acesso fora do escopo e ausência de fallback externo implícito; declarar cobertura parcial quando houver. Não exigir precision/recall mínimos, percentis ou significância estatística para concluir a POC. Registrar os erros encontrados como aprendizado e limitação conhecida.

O conjunto anterior de oito casos permanece uma ampliação opcional. Acrescentar exemplos/repetições quando ajudarem a explicar uma falha, comparar modelos ou mostrar consolidação entre arquivos; não transformar isso em processo de homologação.

## Compartilhamento, disponibilidade e custos

A aplicação terá slots de inferência próprios, fila limitada, deadlines e tratamento de ocupado/timeout. A concorrência da GPU é separada de jobs ativos e de chamadas às plataformas. O `OLLAMA_NUM_PARALLEL=1` observado não limita o projeto a um ou dois trabalhos globais; várias reviews podem estar aguardando contexto ou inferência.

Combinar uso com `divine-touch` e possíveis consumidores do `iam-trainer`; treinamento e múltiplos modelos podem competir pela mesma VRAM. Não mudar parâmetros globais compartilhados sem avaliar esses consumidores. Nenhum cliente Java deve descarregar modelos ou reiniciar o Ollama como recuperação automática.

Fallback externo fica desabilitado por padrão proposto. Indisponibilidade local resulta em falha/reagendamento/parcial visível. Habilitar outro destino exige política explícita de dados e custo. Inferência local não torna toda a review offline: plataformas e servidores remotos continuam usando rede.

Inferência local usa hardware, energia, disco e tempo de operação, portanto não tem custo operacional literalmente zero. Para a POC, registrar essa limitação basta; medição de energia ou depreciação não é condição para avançar.

O login ChatGPT usado no Codex/VS Code é separado da identidade de inferência da aplicação. A [documentação de autenticação](https://learn.chatgpt.com/docs/auth) distingue acesso de assinatura e API. Benefícios de API podem existir para planos elegíveis, mas exigem condições/ativação; não foram verificados para esta conta e não constituem credencial automática para o Java. Fonte: [créditos de API para assinantes](https://developers.openai.com/api/docs/guides/chatgpt-api-credits). **Não presumir que ChatGPT Plus fornece créditos de API à aplicação**, inferência externa incluída ou custo zero. O orçamento da demonstração externa está em [cloud-demo-and-portfolio.md](cloud-demo-and-portfolio.md).

## Atualização futura e rollback — não executados

A consulta ao [release oficial](https://github.com/ollama/ollama/releases/tag/v0.40.16), resolvida por `releases/latest`, retornou **v0.40.16**, posterior ao **0.18.0** encontrado. **Minha sugestão é preparar uma atualização para uma versão estável recente compatível com a NVIDIA e com o modelo escolhido**, conferindo release notes antes de executá-la. Não é necessário esperar uma falha bloquear a POC: suporte a modelo novo e correções úteis já são motivos para atualizar. Isso não garante que toda versão/modelo recente produza reviews melhores; comparar os exemplos verifica o resultado.

Sequência futura proposta:

1. Anotar a imagem/versão atual e preservar o volume `divine-touch-infra_ollama_data` e o bind `/models`; copiar os dados que não possam ser perdidos e guardar a referência da imagem anterior.
2. Escolher release estável compatível e recriar somente o serviço Ollama, em momento em que o outro projeto não esteja usando a inferência. Não remover volumes nem iniciar segunda instância sobre os mesmos dados.
3. Conferir versão, lista de modelos e uma chamada curta usando GPU; testar JSON e uma ferramenta. Essa checagem simples basta para começar, sem ambiente de homologação separado.
4. Experimentar o modelo escolhido e comparar os quatro exemplos. Se aparecer regressão, voltar à imagem anterior e, se necessário, à cópia dos dados.

Atualizar o runtime e baixar um modelo continuam operações distintas, podendo fazer parte da mesma preparação didática. Não apagar os modelos existentes para testar um novo. Não é preciso estabelecer janela formal, backup contínuo ou processo de release de produção.

Atualizar imagem do Ollama e baixar/atualizar modelo são operações distintas. Não há manutenção aprovada ou realizada por este documento.

## Configuração futura dos containers do projeto

Planejar volume próprio para PostgreSQL/trabalhos/auditoria, conteúdo temporário com retenção limitada, configuração versionada sem segredos e referências a segredos montados com acesso mínimo. O gateway não precisa dos pesos do modelo; o agente acessa API e não monta o volume Ollama.

Health/liveness do Java não deve reiniciar serviço de inferência alheio. Readiness pode indicar dependência degradada com diagnóstico de endpoint/modelo; checagens leves de versão/modelos não substituem teste de geração. Adicionar correlation IDs, tempos por etapa e diagnósticos sem código bruto. Não criar Dockerfile ou Compose nesta entrega.

## Verificações pendentes

- Caminho host.docker.internal a partir de container do projeto; alcance LAN/firewall e autenticação/proxy necessários.
- Contexto efetivo, GPU durante inferência, offload, latência, qualidade e capacidade compartilhada.
- RAM/CPU disponibilizadas ao Docker/WSL2 e espaço de disco para cache/backup.
- Compatibilidade real de Spring AI/SDK/modelos com Ollama 0.18.0; necessidade e alvo de atualização.
- Proveniência/template do modelo customizado e política de uso pelos demais clientes.
- Resultados dos exemplos de qualidade/tempo e limites práticos de contexto/chamadas, sem SLO, retenção corporativa ou medição de energia obrigatória.
