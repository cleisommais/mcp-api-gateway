# MCP API Gateway — planejamento

POC de revisão de PRs/MRs com agente Java, MCP Gateway e adaptadores para GitHub, GitLab e Bitbucket. O objetivo é aprender na prática e demonstrar, em portfólio para entrevistas, o fluxo completo com MCP Client, MCP Servers, autorização e auditoria.

**Situação em 12/11/2026:** planejamento documentado; aplicação ainda não implementada. A inspeção inicial encontrou apenas `prompt.md`, sem `AGENTS.md` aplicável ou outros documentos salvos. O conteúdo original foi preservado.

A inferência com o Ollama existente é o padrão de desenvolvimento e validação, sujeito a avaliação de qualidade e capacidade. Para demonstrar independência do computador pessoal, o plano inclui uma VPS temporária com API externa de modelo, sem GPU cloud ou Ollama na VPS inicialmente. O harness Java coordena a revisão; o Ollama serve o modelo local; o gateway governa as ferramentas.

Não se pretende operar serviço comercial nem manter endpoint permanentemente disponível. O repositório será preparado para publicação pública com instruções reproduzíveis, exemplos, avaliações e uma gravação curta. A demonstração termina com exportação das evidências e remoção dos recursos cobrados.

## Documentação

| Documento | Conteúdo principal |
| --- | --- |
| [PLAN.md](PLAN.md) | Escopo, etapas, aprendizado e primeiro exercício |
| [Requisitos](docs/requirements.md) | RF/RNF, aceitação, medição e etapa |
| [Arquitetura](docs/architecture.md) | Componentes, contratos, fluxos e cobertura |
| [Segurança e identidade](docs/security-and-identity.md) | Credenciais, autorização e ameaças |
| [Decisões](docs/decisions.md) | Confirmado, proposto, futuro e alternativas |
| [Questões abertas](docs/open-questions.md) | Pendências e quando bloqueiam |
| [Infraestrutura local](docs/local-inference.md) | Inventário verificado, Ollama, rede, benchmark e atualização futura |
| [Demonstração cloud e portfólio](docs/cloud-demo-and-portfolio.md) | VPS temporária, comparação de APIs, orçamento, evidências e encerramento |

As classificações de decisão estão em [decisions.md](docs/decisions.md). Uma proposta técnica não se torna aprovada por aparecer em um diagrama ou critério de teste.

## Resultado da inspeção

O usuário confirmou RTX 5060 Ti com 16 GB de VRAM, Ryzen 9 5950X, aproximadamente 64 GiB de RAM e Ollama **0.18.0** pela infraestrutura `divine-touch-infra`, com `qwen3:14b`, `gpt-oss:20b` e `divine-touch-30b:latest` instalados. Esses dados correspondem à inspeção anterior; nenhum modelo estava carregado naquela consulta. Desempenho e inferência em GPU continuam sem validação. Evidências e limites estão no [inventário](docs/local-inference.md).

## Próximo passo

Após nova instrução para implementar, iniciar a **F1**: cliente Java descobre e chama uma ferramenta MCP determinística, sem IA, credenciais Git ou alteração da infraestrutura. O gateway permanece na arquitetura final e entra na F3.

Esta entrega criou somente Markdown. Não instalou dependências, executou inferência, baixou modelos, iniciou ou atualizou containers, criou serviços, nem realizou commit, push ou deploy.
