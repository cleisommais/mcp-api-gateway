# Decisões e propostas

[Índice](../README.md) · [Plano](../PLAN.md). Registro em 12/11/2026, derivado de `prompt.md`, da inspeção local e do complemento do usuário sobre POC, cloud temporária e portfólio.

## Classificação

- **C — Confirmada pelo usuário:** direção explicitamente indicada. Não aprova automaticamente tecnologia ou valor sugerido para realizá-la.
- **P — Proposta técnica inicial:** recomendação ajustável, ainda sem aprovação.
- **Q — Questão em aberto:** depende de escolha ou evidência; ver [open-questions.md](open-questions.md).
- **F — Evolução futura:** fora da entrega inicial proposta, sem compromisso de data.

Constatações do ambiente são evidências, não decisões. Os IDs RF/RNF usam esta classificação para o requisito; quando a estratégia de implementação for proposta, ela é identificada no texto.

## Direções confirmadas

| ID | Status | Direção |
| --- | --- | --- |
| D01 | C | Usar Java e desenvolver em incrementos explicados, planejando antes de implementar |
| D02 | C | Manter MCP Gateway no desenho e no aprendizado, mesmo que introduzido gradualmente |
| D03 | C | Executar localmente em containers Docker |
| D04 | C | Projetar para GitHub, GitLab e Bitbucket, sem núcleo preso à API GitHub |
| D05 | C | Permitir múltiplos provedores/modelos, inclusive locais |
| D06 | C | Suportar PRs grandes sem limite fixo de 20 arquivos e declarar cobertura incompleta |
| D07 | C | Concorrência configurável para vários repositórios, sem fixar dois trabalhos globais |
| D08 | C | Aproveitar MCP Servers existentes quando atenderem às necessidades |
| D09 | C | Reaproveitar Ollama e GPU NVIDIA existentes para o primeiro caminho de inferência, sujeito à qualidade e capacidade |
| D10 | C | Nesta tarefa, somente Markdown; consultas aos outros projetos em leitura; sem infraestrutura, código, commit ou push |
| D11 | C | Finalidade de aprendizado e portfólio de entrevistas, sem operação comercial ou instalação permanentemente disponível |
| D12 | C | Ollama existente como padrão de desenvolvimento/validação; avaliar modelos já instalados antes de novos downloads; sem atualização automática |
| D13 | C | Demonstrar em VPS temporária com API externa de modelo, independente do computador pessoal; inicialmente sem GPU cloud nem migração do Ollama para a VPS |
| D14 | C | Escolher inicialmente um provedor externo por qualidade e custo total por review; endpoint, modelo e credenciais configuráveis; sem fallback externo automático implícito |
| D15 | C | Preparar repositório público com documentação, exemplos reproduzíveis, avaliações e gravação curta, sem credenciais ou informações privadas |
| D16 | C | Exportar evidências e remover VM e recursos cobrados ao concluir a demonstração; não depender de endpoint permanente |
| D17 | C | Dimensionar escolhas e validações como POC de aprendizado; não exigir perguntas, SLOs ou processos operacionais de produção para avançar |
| D18 | C | Atualizar Ollama se necessário e considerar modelo mais atual/apropriado à GPU; runtime/modelos existentes não são restrições permanentes |

Hardware, versão e modelos foram **confirmados pelo usuário**, além da evidência da inspeção anterior. O registro canônico está em [local-inference.md](local-inference.md); características técnicas do modelo customizado não são inferidas do nome nem incluídas automaticamente nessa confirmação.

## Propostas e tradeoffs

| ID | Status | Proposta | Alternativa e consequência |
| --- | --- | --- | --- |
| P01 | P | Java 21, Maven, Spring Boot, Spring Security e Spring AI, inicialmente imperativos | SDK MCP e cliente HTTP sem framework reduzem abstrações na F1; mais integração manual depois |
| P02 | P | Aplicação única com entrada, worker, núcleo e publicador; gateway em outro processo | Separar todos em microserviços amplia operação sem evidência de necessidade |
| P03 | P | PostgreSQL próprio para trabalhos, publicação e auditoria; fila no banco | Fila em memória não atende recuperação; broker pode ser revisto após medição |
| P04 | P | Docker Compose para aplicação/banco nos dois perfis; no local, referenciar inferência existente | Duplicar Ollama/modelos consome disco/VRAM e dificulta propriedade e manutenção; cloud usa API externa |
| P05 | P | GitHub primeiro e GitHub App para automação | PAT/machine user exigem avaliação de permissões, ciclo de vida e dependência pessoal |
| P06 | P | Modelo recebe ferramentas de leitura; aplicação publica após validação | Escrita pelo modelo aumenta impacto de prompt injection e dificulta idempotência |
| P07 | P | MVP estático sem shell, execução de PR, merge ou aprovação automática | Execução futura exige sandbox e outro desenho de risco |
| P08 | P | Comparação curta de `qwen3:14b` existente com `qwen3.5:9b`, após conferir suporte do runtime | `gpt-oss:20b` é comparação opcional; não exigir teste exaustivo de todos os instalados nem inferir qualidade pela novidade |
| P09 | P | No perfil local, acessar Ollama por `http://host.docker.internal:11434` inicialmente | Rede compartilhada reduz dependência do host, mas conecta a aplicação à rede com outros serviços; detalhes no inventário |
| P10 | P | Fallback externo desabilitado por padrão, habilitação explícita por política/repositório | Continuidade automática pode divulgar código e gerar custos não autorizados |
| P11 | P | Separar rodadas de ferramentas da resposta final por JSON Schema | Combinação de ferramentas e schema na mesma chamada precisa validação por modelo/versão |
| P12 | P | Começar com uma chamada local de inferência por vez e fila limitada na aplicação | É valor inicial de experimento da GPU compartilhada, não limite global de reviews |
| P13 | P | Uma VPS com Docker Compose, inicialmente 2 vCPUs e 4 GB de RAM | Medir capacidade de JVMs, banco e servidores MCP; sem banco gerenciado, load balancer pago ou Kubernetes inicialmente |
| P14 | P | Priorizar recurso gratuito/crédito de baixo esforço, depois VPS proporcional ao uso | Oracle Always Free, DigitalOcean e Hetzner são candidatos; nenhuma contratação ou elegibilidade foi confirmada |
| P15 | P | Orçamento total inicial de US$ 5–10 para infraestrutura e inferência da demonstração | Meta a validar; reservar margem para adicionais, tributos/câmbio e atraso de medição; créditos não são garantidos |
| P16 | P | Obter diff e arquivos do commit pelas ferramentas MCP da plataforma, inicialmente sem exigir clone completo | Clone/fetch pode ser uma implementação da ferramenta se for útil depois; não exige executar código da PR ou oferecer shell ao modelo |

Os candidatos de API, referências econômicas datadas, comparação e encerramento estão em [cloud-demo-and-portfolio.md](cloud-demo-and-portfolio.md), evitando replicar preços nestas decisões. Provedor, SKU/região da VPS e limites finais continuam **Q**.

## Compatibilidade consultada

As fontes abaixo foram consultadas em 12/11/2026. São bases para seleção futura; nenhum BOM, imagem ou versão de dependência foi fixado.

- A documentação consultada de **Spring AI 2.0.2** declara suporte a **Spring Boot 4.0.x e 4.1.x**. Boot 4.1.2 declara Java 17–27 e Maven 3.6.3+. Assim, Java 21/Maven é uma combinação candidata compatível nessas condições. Não misturar uma linha arbitrária de Boot com outra de Spring AI. Usar BOMs compatíveis, inclusive Spring Security gerenciado por Boot, e confirmar o conjunto no build da F1/F2. Fontes: [Spring AI](https://docs.spring.io/spring-ai/reference/getting-started.html), [Boot](https://docs.spring.io/spring-boot/system-requirements.html).
- O [SDK Java oficial](https://java.sdk.modelcontextprotocol.io/latest/) oferece clientes/servidores síncronos e assíncronos. A documentação distingue o suporte habitual do preview de servidor para a revisão MCP **2026-07-28**. O suporte integral cliente/gateway/downstream a essa revisão não foi comprovado. Escolher um perfil comum testável; não adotar `latest` implicitamente. Transportes Spring são fornecidos por Spring AI 2.0+; verificar a matriz concreta ao selecionar dependências.
- A integração [Spring AI Ollama](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html) documenta endpoint, modelo, contexto, ferramentas e saída por schema. Propõe-se a integração nativa inicialmente, com download automático desabilitado. Os recursos ainda precisam de teste contra o servidor **0.18.0** encontrado. A documentação atual não constitui prova de compatibilidade retroativa.
- O [servidor oficial GitHub](https://github.com/github/github-mcp-server) é candidato, não escolha final. Sua [documentação de GitHub App](https://github.com/github/github-mcp-server/blob/main/docs/github-app-auth.md) descreve autenticação de instalação no modo local `stdio`, com renovação de token, e informa que esse modo não se aplica ao comando HTTP. Validar a versão distribuída e o modo escolhido; isso não comprova compatibilidade do servidor remoto com o mesmo fluxo.
- O [release oficial do Ollama](https://github.com/ollama/ollama/releases/tag/v0.40.16) resolvido pela consulta de `releases/latest` é posterior ao 0.18.0 encontrado. Isso justifica avaliar atualização, mas não aprova migrar diretamente para esse alvo. A avaliação e o rollback estão em [local-inference.md](local-inference.md).

## Evoluções futuras

| ID | Status | Evolução | Condição |
| --- | --- | --- | --- |
| E01 | F | Comentários por linha | Mapear diff/commit/posição e validar API de cada oferta |
| E02 | F | OAuth delegado interativo/local | Operação realmente precisar ocorrer em nome do usuário |
| E03 | F | Catálogo corporativo integrado | Ter registry aprovado e governança definida |
| E04 | F | Escala distribuída e infraestrutura adicional | Benchmark demonstrar necessidade |
| E05 | F | Modelos adicionais, tuning e roteamento por tarefa | Comparação de qualidade, custo e contexto justificar |

## Metas ainda não aprovadas

ACK p95 de 2 s, review p95 de 3 min, timeout total de 5 min, dez reviews simultâneas, duas por repositório e uma ativa por PR/SHA são **exemplos anteriores**, não compromissos. Não aplicá-los como padrão aprovado nem como capacidade comprovada. Metas devem identificar hardware, cotas, tamanho, modelo e orçamento. O planejamento de medição está em [requirements.md](requirements.md).

Conforme a orientação mais recente, esta POC não precisa definir SLOs, percentis de desempenho, política corporativa de retenção ou garantias de fairness para ser concluída. Registrar valores de alguns exemplos e limitações basta. Os controles básicos de credencial, autorização, saída, cobertura e custo continuam porque fazem parte do fluxo e do aprendizado.

O tamanho inicial da VPS e a meta de US$ 5–10 também são **P**, não garantia de desempenho/custo nem permissão de provisionamento nesta tarefa.
