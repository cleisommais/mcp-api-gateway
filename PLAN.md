# Plano de entrega e aprendizado

Referência de planejamento: 12/11/2026. [Índice](README.md) · [Decisões](docs/decisions.md) · [Requisitos](docs/requirements.md).

## Objetivo e limites

Construir uma POC de PR review para aprendizado prático e portfólio de entrevistas, com agente Java, MCP Gateway, acesso a ferramentas MCP e provedores de modelos intercambiáveis. O núcleo deve funcionar para GitHub, GitLab e Bitbucket por contratos independentes da plataforma. O desenvolvimento ocorre no VS Code e a execução pretendida é em Docker.

**Confirmado:** planejamento antes da implementação; Java; gateway no desenho final; containers; múltiplas plataformas e modelos; PRs grandes sem corte fixo de arquivos; concorrência configurável; reaproveitamento de servidores adequados; Ollama e hardware existentes como caminho inicial de inferência, sujeito a validação.

**Complemento confirmado:** Ollama local como padrão de iteração para evitar cobrança de inferência externa; avaliar modelos instalados antes de novos downloads; demonstração temporária em VPS com API externa, independente do computador pessoal; um provedor externo inicial escolhido por avaliação; repositório preparado para publicação pública e encerramento da infraestrutura após a demonstração. Não há objetivo de serviço comercial ou disponibilidade permanente. Não contratar GPU cloud nem levar Ollama à VPS inicialmente.

Custos, candidatos e critérios de encerramento estão centralizados em [cloud-demo-and-portfolio.md](docs/cloud-demo-and-portfolio.md). **P:** uma máquina com Docker Compose, inicialmente 2 vCPUs/4 GB, e orçamento total de US$ 5–10, ambos sujeitos a medição e revalidação; esses números não são capacidade comprovada nem autorização de gasto nesta tarefa.

**Proposta de MVP:** análise estática, relatório manual primeiro, automação GitHub depois, resumo publicado pela aplicação e modelo com ferramentas apenas de leitura. Sem executar código da PR, shell genérico, alteração de código, merge ou aprovação automática. Comentários por linha ficam para evolução. Detalhes de stack permanecem propostas em [decisions.md](docs/decisions.md).

Não iniciar Kubernetes, Kafka, Redis, múltiplos agentes especializados ou infraestrutura distribuída sem necessidade demonstrada. A existência desses componentes no `iam-trainer` não justifica copiá-los.

**Escala da POC:** usar as sugestões de [open-questions.md](docs/open-questions.md) para resolver escolhas técnicas no próprio exercício, sem exigir respostas do usuário a cada detalhe. Não há SLO/SLA, alta disponibilidade, política corporativa de retenção ou homologação formal como condição de entrega. Logs simples, poucos exemplos conhecidos e controles básicos de credencial, autorização e gasto são suficientes. Atualizar Ollama e experimentar modelo mais adequado quando isso facilitar a inferência local faz parte da preparação, sem ficar preso ao ambiente antigo.

## Etapas propostas

Os entregáveis abaixo descrevem trabalho futuro; não foram implementados nesta entrega. Segurança mínima, validação e relato honesto de cobertura acompanham a primeira funcionalidade que depende deles; a F7 amplia a robustez, sem adiar essas proteções.

### F1 — Fundamentos MCP, sem modelo

- **Aprender:** host, cliente, servidor, descoberta, schema, chamada, resultado e transporte. Retomar records, interfaces, exceções e build Java em um exemplo pequeno.
- **Entregar:** cliente Java e um servidor didático com uma ferramenta de leitura de fixture determinística. Escolher um perfil de protocolo suportado pelos dois lados, fixar dependências e documentar execução em Docker. `stdio` é uma opção didática; HTTP pode demonstrar containers independentes.
- **Dependências:** selecionar JDK/build/SDK compatíveis e o transporte. Java 21/Maven e SDK Java oficial são propostas. Não depende de GitHub App, GPU ou modelo.
- **Concluir quando:** descobrir a ferramenta, mostrar o schema, chamá-la com argumento válido e tratar argumento inválido e servidor indisponível de forma compreensível. Registrar o perfil de protocolo e encerrar a conexão corretamente.
- **Validar:** resultado conhecido da fixture, erro de schema e falha de conexão; observar mensagens sem credenciais. Confirmar o exercício no ambiente Docker escolhido.
- **Depois:** IA, diffs reais, gateway com políticas, autenticação delegada e publicação.

### F2 — Review manual com inferência local

- **Aprender:** transformação de diff em contexto, diferenças entre ferramenta MCP e chamada ao modelo, janela de contexto e validação da saída.
- **Entregar:** entrada manual com plataforma, conexão/repositório e número da PR/MR; obtenção de metadados/diff/contexto via MCP e SHA fixo; relatório local estruturado com cobertura. Usar fixtures antes do repositório real. Integrar o Ollama existente sem criar outra instância.
- **Dependências:** F1; servidor que forneça as operações necessárias; credencial de leitura para dados reais; acesso ao endpoint e seleção experimental do modelo. Fazer as checagens simples e os quatro exemplos de [local-inference.md](docs/local-inference.md).
- **Concluir quando:** um modelo adequado à GPU produzir achados verificáveis e JSON aceito; comparar inicialmente o baseline `qwen3:14b` com o candidato `qwen3.5:9b`, atualizando o runtime quando necessário. Rejeitar arquivo/localização inventados, argumentos fora do escopo e saída inválida; paginar arquivos e evidenciar análise parcial em PR maior que o contexto.
- **Validar:** quatro exemplos conhecidos, incluindo mais de 20 arquivos em um deles; conferir tratamento de conteúdo truncado, timeout e schema inválido. Observar carga fria/quente se for útil ao aprendizado. Não publicar automaticamente nessa etapa.
- **Depois:** automação, comentários por linha e otimizações motivadas pelo uso. Tool calling pelo modelo é avaliado, mas o harness pode inicialmente buscar contexto de forma determinística.

### F3 — Gateway mínimo governado

- **Aprender:** gateway como servidor upstream e cliente downstream; roteamento, identidade e autorização por recurso.
- **Entregar:** processo Java separado com cadastro administrativo de servidor, catálogo permitido, mapeamento de ferramentas, validação por chamada e auditoria. Migrar o acesso direto experimental da F2 para agente → gateway → servidor.
- **Dependências:** F1/F2; matriz de transporte/protocolo; emissor apropriado para tokens, se adotado OAuth. Não criar um IdP próprio. A identidade local do laboratório deve ser explícita e restrita.
- **Concluir quando:** cada chamada carregar escopo de trabalho/repositório/SHA, negar acesso cruzado, ferramenta não permitida e destino não cadastrado; credenciais downstream permanecerem fora do modelo. Conectar agente e gateway como containers independentes.
- **Validar:** casos permitidos/negados, argumentos adulterados, troca de repositório, servidor indisponível, mudança de schema e correlação ponta a ponta.
- **Depois:** catálogo empresarial completo, múltiplas organizações e delegação interativa ampla.

### F4 — Automação, persistência e publicação

- **Aprender:** webhook autenticado, instalação de aplicação, trabalho durável, recuperação e efeitos externos.
- **Entregar:** GitHub como primeira integração proposta; endpoint valida o webhook, salva a análise pendente e responde à plataforma confirmando recebimento. Uma rotina em segundo plano da mesma aplicação Java executa o agente, que usa credenciais apropriadas, respeita branches/drafts e entrega o resumo à publicação. PostgreSQL e fila simples no banco são propostas, sem broker adicional; sequência explicada em [architecture.md](docs/architecture.md).
- **Dependências:** F3; GitHub App instalada e permissões definidas; servidor MCP compatível com automação; banco próprio; endpoint HTTPS acessível. Para webhook local, avaliar túnel; GitHub não alcança diretamente `localhost`.
- **Concluir quando:** entrega duplicada gerar um trabalho lógico; reinício recuperar trabalho; novo commit invalidar revisão anterior; publicação registrar SHA e cobertura e lidar com resultado ambíguo sem duplicar deliberadamente o resumo.
- **Validar:** assinatura inválida, evento irrelevante, draft, branch filtrada, revogação de instalação, expiração de token, rate limit, crash após publicação e PR atualizada durante análise. Distinguir tempo de resposta ao webhook do tempo de execução da review.
- **Depois:** outras plataformas e comentários por linha. Alta disponibilidade não é objetivo desta POC temporária.

### F5 — Segundo servidor ou plataforma

- **Aprender:** o que MCP padroniza e o que precisa de tradução semântica.
- **Entregar:** segundo servidor ou adaptador, preferencialmente de outra plataforma, passando os mesmos testes de contrato. Se apenas trocar servidor GitHub, registrar que isso não valida GitLab/Bitbucket.
- **Dependências:** F3/F4; oferta alvo escolhida (cloud ou versão self-hosted), servidor aprovado e credencial compatível.
- **Concluir quando:** identificar alteração, enumerar arquivos, ler por SHA, produzir relatório e publicar resumo sem modificar regras de análise do núcleo. Declarar capacidades ausentes.
- **Validar:** paginação, diffs omitidos, renomeações, estado atual, permissões e localização de comentários na oferta selecionada.
- **Depois:** cobertura das demais ofertas e comentários por linha com tradução específica.

### F6 — Segundo modelo ou provedor

- **Aprender:** comparar qualidade, custo, contexto, uso de ferramentas e latência sem acoplar o harness ao provedor.
- **Entregar:** comparar modelos locais já instalados e fazer uma triagem pequena de candidatos externos em PRs conhecidas, com orçamento explícito. Selecionar inicialmente apenas um provedor externo para a demonstração, registrando qualidade e custo total por review. Esta etapa compara alternativas; o primeiro uso de modelo local já ocorre na F2.
- **Dependências:** F2/F3; benchmark reproduzível; consentimento/política de envio de dados se sair do ambiente local. Não exige atualizar Ollama automaticamente.
- **Concluir quando:** mudar endpoint/modelo pela configuração, manter o contrato de achados e demonstrar bloqueio de fallback externo não autorizado.
- **Validar:** qualidade por caso, saída estruturada, ferramentas, indisponibilidade, consumo, tempo e orçamento nas mesmas condições; contabilizar contexto reenviado, chamadas adicionais, retries e raciocínio cobrado. Endpoint, modelo e referência de credencial permanecem configuráveis; não tratar Plus como crédito de API.
- **Depois:** seleção dinâmica por tarefa, modelos adicionais e otimizações justificadas por medição.

### F7 — Experimentos de concorrência, falhas e segurança

- **Aprender:** limites reais da GPU, concorrência, cobertura e resistência a falhas.
- **Entregar:** um exemplo com dois repositórios, controles separados de jobs e inferência, uma PR maior que o contexto e testes pontuais de falha/injeção. Fair scheduling avançado fica opcional.
- **Dependências:** fluxo básico funcionando; escolher um experimento por vez conforme o aprendizado, sem metas de produção pendentes para aprovar.
- **Concluir quando:** conseguir explicar concorrência, falha e cobertura nos exemplos executados, sem acesso fora do escopo ou cobertura parcial apresentada como completa.
- **Validar:** uma indisponibilidade/timeout, um reinício e os casos de prompt injection e bug entre arquivos; registrar resultados e limitações. Não exigir teste de carga, caos ou amostra estatística.
- **Depois:** aprofundar apenas o que for útil ao aprendizado ou a um problema observado; infraestrutura distribuída não é objetivo desta POC.

### F8 — Demonstração temporária e entrega de portfólio

- **Aprender:** operar o fluxo completo fora do computador pessoal, medir custo e reproduzir o ciclo de criação, demonstração e encerramento.
- **Entregar:** instruções reproduzíveis para uma VPS com Docker Compose, webhook HTTPS direto, persistência, agente, ferramentas pelo gateway, API externa selecionada e resumo publicado na PR. Preparar README, exemplos sintéticos, resultados, limitações e gravação curta para repositório público.
- **Dependências:** F4 e seleção externa da F6, controles de segurança/orçamento aplicáveis e fluxo validado localmente. A demonstração pode ocorrer antes de F5 e do endurecimento completo da F7; não exige todas as plataformas nem promessa de capacidade comercial.
- **Concluir quando:** abrir/atualizar PR produz resultado na PR sem depender do desktop/Ollama pessoal, permite correlacionar evento/trabalho/MCP/modelo/publicação e preserva evidências depois da exclusão da VM. Não é obrigatório usar pipeline Git para disparar o fluxo.
- **Validar:** configuração candidata 2 vCPUs/4 GB por CPU/RAM/fila/latência, preço e crédito elegível antes do provisionamento, limites de chamadas/tokens/retries/duração, sanitização dos artefatos e inventário final de recursos cobrados. Medir custo bruto e desembolso após créditos, conforme [plano cloud](docs/cloud-demo-and-portfolio.md).
- **Depois:** remover infraestrutura temporária e conferir cobrança residual; manter apenas evidências e instruções públicas. Não manter endpoint online para entrevistas.

Documentação e exemplos de portfólio evoluem em cada fase; publicar código/repositório, gravar a demonstração e provisionar recursos são tarefas futuras, não ações desta atualização de planejamento.

## Primeiro exercício hands-on recomendado

Implementar, após autorização para a próxima tarefa, uma ferramenta didática conceitual `get_review_fixture` que recebe um identificador de cenário e devolve um pequeno diff conhecido. O cliente descobre o schema, chama o cenário válido e tenta um identificador inválido. Esse nome é uma proposta de exercício, não contrato aprovado.

Explicar cada peça e executar sem IA. O sucesso é compreender a mensagem que cruza a fronteira MCP e o erro retornado. A escolha pontual de SDK/versão/transporte deve ser registrada antes do build. As [questões abertas](docs/open-questions.md) distinguem essas escolhas dos bloqueios de etapas posteriores.

## Limite desta entrega

Somente documentação e diagnóstico de leitura. Atualização de imagem, download de modelo, alteração de rede/volume, instalação de dependências e implementação aguardam nova instrução. A sequência futura de manutenção está no [plano de inferência](docs/local-inference.md).
