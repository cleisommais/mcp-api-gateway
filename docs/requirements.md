# Requisitos e aceitação

[Índice](../README.md) · [Etapas F1–F8](../PLAN.md) · [Classificação C/P/Q/F](decisions.md).

Os critérios são verificações planejadas, não resultados já obtidos. **C** identifica o escopo explicitamente solicitado; decisões de implementação ainda propostas aparecem como **P** no critério. **F** identifica evolução. A etapa informa a primeira entrega e, quando necessário, o endurecimento posterior. Nenhum requisito abaixo está implementado.

## Funcionais

| ID | Requisito | Status | Etapa | Critério de aceitação verificável |
| --- | --- | --- | --- | --- |
| RF01 | Disparo manual | C | F2 | Receber plataforma, conexão/repositório e número de PR/MR; validar identidade e existência; produzir resultado ou motivo de recusa rastreável |
| RF02 | Disparo automático | C | F4 | Evento relevante autenticado cria trabalho durável; evento inválido ou irrelevante não inicia review; conjunto inicial de eventos por oferta é P |
| RF03 | Filtro de branch de destino | C | F4 | Configuração por repositório permite/rejeita destinos e registra razão; testar `Development` como exemplo configurável, sem tratá-la como branch obrigatória |
| RF04 | Tratamento de drafts | C | F4 | Aplicar política configurada e testar transições draft/ready; proposta P: ignorar drafts por padrão e iniciar ao ficarem prontos |
| RF05 | Dados via MCP | C | F1, F2 | Descobrir e chamar ferramentas com schema; metadados, arquivos, diffs e contexto da review passam por MCP; distinguir chamada MCP da consulta ao modelo |
| RF06 | Snapshot por commit | C | F2 | Fixar base/head SHA e identidade do repositório antes da análise; toda leitura de conteúdo aponta ao SHA registrado, nunca a branch mutável |
| RF07 | Achados estruturados | C | F2 | Cada achado tem arquivo, localização válida, severidade, explicação e evidência ligada ao SHA; ausência de evidência impede publicação do achado |
| RF08 | Validação de saída | C | F2 | Rejeitar JSON/schema inválido, severidade desconhecida, arquivo/linha inexistentes e texto excessivo; registrar falha ou correção limitada sem loop ilimitado |
| RF09 | Resumo com commit e cobertura | C | F2 local, F4 publicação | Relatório informa SHA, arquivos/hunks analisados, exclusões, pendências e limites; publicador confirma estado atual antes de enviar e persiste identificador remoto |
| RF10 | Comentários por linha | F | Após F4/F5 | Publicar apenas em posições válidas do diff correspondente; não reutilizar linha de snapshot antigo; oferta sem capacidade continua com resumo |
| RF11 | Estado e motivo de falha | C | F2 básico, F4 durável | Consultar estado, tempos, tentativa e código de falha; distinguir indisponível, cancelado, obsoleto, inválido e parcial; proposta de estados na arquitetura |
| RF12 | Deduplicação de eventos | C | F4 | Reentregar mesmo ID de evento não cria execução lógica duplicada; chave também inclui plataforma/conexão para evitar colisões |
| RF13 | Proteção contra publicação repetida | C | F4 | Retry após resposta perdida reconcilia marcador/ID remoto antes de reenviar; mesmo resultado lógico não gera resumos repetidos nos cenários de teste |
| RF14 | Commits durante a review | C | F2, F4 automático | Ao detectar head novo, marcar resultado antigo como obsoleto e não publicar como atual; proposta P: cancelar trabalho antigo e agendar novo snapshot |
| RF15 | Reprocessamento manual | C | F4 | Pedido autorizado gera tentativa explícita, com razão e vínculo ao original; distingue reanalisar SHA anterior de acompanhar head atual |
| RF16 | Servidores MCP aprovados | C | F3 | Apenas cadastro administrativo permite URL/processo, transporte, versão e referência de credencial; mudança fica auditada; modelo não cadastra servidores |
| RF17 | Catálogo permitido e roteamento | C | F3 | Expor somente ferramentas aprovadas; resolver ferramenta para servidor/conexão corretos; colisão de nomes e alteração de schema exigem tratamento explícito |
| RF18 | Autorização por chamada | C | F3 | Validar identidade, ferramenta, plataforma, conexão e repositório em cada invocação; negar recurso fora do trabalho, mesmo com ferramenta visível |
| RF19 | Provedor/modelo selecionáveis | C | F2, F6 comparação | Alterar endpoint/modelo por configuração validada sem mudar o núcleo; registrar provedor, modelo e versão/digest disponível em cada execução |
| RF20 | Orçamentos de execução | C | F2, F7 capacidade | Configurar limites de tokens de entrada/saída, chamadas de ferramenta/modelo, custo e duração; exaustão encerra ou produz parcial com razão, sem exceder deliberadamente o limite |
| RF21 | Auditoria e rastreabilidade | C | F3, F4 durável | Correlacionar trabalho, tentativa, evento, identidade, autorização, MCP, modelo e publicação; registrar decisões sem tokens ou código bruto por padrão |
| RF22 | Paginação e truncamento | C | F2 | Percorrer páginas até fim confirmado; testar mais de 20 arquivos, página vazia, repetida, rate limit e diff truncado; fonte incompleta impede declaração de cobertura total |
| RF23 | PRs grandes e contexto | C | F2 básico, F7 | Classificar arquivos, dividir por tokens, recuperar contexto controlado e consolidar; relatório contabiliza analisados, excluídos e pendentes; medir caso com bug entre arquivos |
| RF24 | Disponibilidade do Ollama | C | F2 | Consultar versão/modelos sem gerar inferência; endpoint ausente, versão incompatível ou modelo não instalado resulta em diagnóstico claro; aplicação não faz pull ou inicia outra instância implicitamente |
| RF25 | Timeout e indisponibilidade local | C | F2 | Simular timeout/erro/serviço ocupado; encerrar tentativa ou reagendar com limite; liberar slot da aplicação e registrar possível inferência remanescente no servidor |
| RF26 | Argumentos e JSON separados da política | C | F2/F3 | Validar schema e semântica antes de cada ferramenta; SHA, repositório e caminhos são confrontados com contexto autorizado, mesmo se gerados pelo modelo |
| RF27 | Fallback externo explícito | C | F2/F6 | Falha local não envia código a outro provedor automaticamente; teste verifica ausência de chamada externa sem política habilitada; habilitação exige destino e escopo de dados definidos |
| RF28 | Perfis local e demonstração cloud | C | F2/F6/F8 | Desenvolvimento usa Ollama existente; demonstração usa API externa explicitamente configurada e completa webhook → trabalho persistido → agente → ferramentas via gateway → modelo → publicação, sem desktop, GPU cloud ou Ollama na VPS inicialmente |
| RF29 | Evidências exportáveis | C | Todas; conclusão F8 | Exportar exemplos publicáveis, resultados rotulados, métricas, decisões e gravação curta; arquivos continuam acessíveis após remover VPS, sem depender de endpoint online |
| RF30 | Encerramento da demonstração | C | F8 | Runbook identifica recursos da campanha, exporta evidências, exclui VM/adicionais cobrados e verifica saldo/cobrança residual; desligar a máquina não é aceito como prova de encerramento; execução é operacional, fora das ferramentas do modelo |

## Não funcionais

Os IDs anteriores foram preservados, mas os critérios foram dimensionados para **aprendizado e demonstração**. Não há SLO/SLA, disponibilidade permanente, p95 obrigatório, teste estatístico de capacidade ou política corporativa de retenção a aprovar. Medir o comportamento de alguns exemplos é suficiente para registrar resultados e limitações. Timeouts, limites de concorrência e orçamento são configurações práticas, escolhidas durante o exercício sem abrir um questionário de produto.

| ID | Propriedade | Status | Etapa | Medição e critério de aceitação |
| --- | --- | --- | --- | --- |
| RNF01 | Confirmação de recebimento do webhook | C | F4 | Salvar análise pendente antes de responder com sucesso HTTP à plataforma; registrar o tempo observado e executar a review em segundo plano na mesma aplicação Java, sem esperar o modelo para responder ao webhook; sem meta p95 |
| RNF02 | Tempo de review observado | C | F2/F7 | Registrar duração de algumas PRs com tamanho/modelo/contexto e distinguir fila de inferência; apresentar os valores medidos, sem prometer latência |
| RNF03 | Concorrência configurável | C | F4/F7 | Testar limites independentes de jobs ativos, por repositório, chamadas ao modelo e chamadas à plataforma; alteração de configuração modifica comportamento sem recompilar |
| RNF04 | Distribuição justa avançada | F; fora da POC inicial | Opcional após F7 | Preservar como exercício posterior se aparecer monopolização real; não exigir algoritmo de fairness, cota por cliente ou garantia de espera para concluir a POC |
| RNF05 | Persistência e reinício simples | C | F4 | Demonstrar que um trabalho salvo continua visível após reiniciar a aplicação e pode ser retomado ou reprocessado; documentar limitações de recuperação |
| RNF06 | Evitar publicação duplicada | C | F4 | Reentregar um evento e repetir uma tentativa; conferir que o exemplo atualiza/reutiliza o resumo identificado em vez de criar spam; sem alegação de exactly-once |
| RNF07 | Timeout e retries limitados | C; valores P | F2/F4 | Configurar timeout e poucas tentativas, registrar erro e respeitar rate limit; demonstrar uma falha sem loop infinito, sem bateria de testes de resiliência |
| RNF08 | Isolamento demonstrável | C | F3/F7 | Demonstrar uma chamada permitida e outra negada para repositório fora do escopo; manter conexão/repositório/SHA explícitos, sem matriz multitenant de produção |
| RNF09 | Logs e correlação simples | C | F3/F4 | Encontrar uma execução por jobId nos logs e observar MCP/modelo/publicação; console e registros no banco bastam, sem stack de monitoramento obrigatória |
| RNF10 | Credenciais e dados de demonstração | C | F2/F4 | Usar dados demonstrativos, segredos fora do Git e logs sem tokens; exportar evidências sanitizadas e descartar temporários ao encerrar; sem política corporativa de retenção ou backup contínuo |
| RNF11 | Orçamento e custo | C; limites Q | F2/F6/F8 | Contabilizar todas as tentativas, contexto reenviado, chamadas adicionais, cache e raciocínio faturável sem dupla contagem; separar custo local, cloud bruto, créditos e desembolso; ao atingir limite de tokens/chamadas/custo, parar com indicação parcial |
| RNF12 | Configuração reproduzível | C | F1/F4 | Anotar dependências, imagens, protocolo, modelo e parâmetros usados, com instruções e placeholders de segredos; outra pessoa consegue repetir o exemplo |
| RNF13 | Compatibilidade MCP usada | C | F1/F3/F5 | Demonstrar descoberta, chamada e erro no perfil escolhido, incluindo autorização quando houver gateway; não exigir certificação ou matriz de todas as versões |
| RNF14 | Qualidade em exemplos conhecidos | C | F2/F6/F7 | Avaliar quatro casos iniciais e registrar achados corretos, falsos positivos e falhas; sem precision/recall mínimos ou aprovação estatística para avançar |
| RNF15 | Uso local de GPU | C | F2/F7 | Observar GPU/VRAM e tempo durante uma chamada curta com o modelo/contexto escolhido; reduzir contexto ou trocar modelo se faltar memória, sem teste de capacidade contínuo |
| RNF16 | Fila de inferência limitada | C; política P | F2/F7 | Limitar concorrência e `Q_max` na aplicação; serviço ocupado não causa fila infinita; job permanece observável; iniciar ensaio com uma chamada é P e não limita jobs globais |
| RNF17 | Proteção do endpoint local | C | F2/F3 | Apenas destinos aprovados são usados pela aplicação; confirmar alcance de rede/firewall e ausência de exposição pública planejada; autenticação/proxy se necessário, sem inventar proteção nativa inexistente |
| RNF18 | Contexto efetivo | C | F2/F7 | Registrar `num_ctx` por chamada, tokens de prompt/schema/ferramentas/saída e contexto observado no servidor; se não couber, dividir ou declarar parcial; contexto nominal do modelo não comprova capacidade disponível |
| RNF19 | Capacidade da VPS temporária | C; tamanho P | F8 | Medir CPU, RSS/heap, RAM total, disco, fila e latência no cenário de demonstração; proposta 2 vCPUs/4 GB só é aceita para a carga medida, sem OOM ou perda de trabalhos; registrar arquitetura e compatibilidade de imagens ARM quando aplicável |
| RNF20 | Orçamento total da campanha | C; meta P | F6/F8 | Meta inicial US$ 5–10, a validar e fixar antes do gasto; comparar estimativa e custo real de infraestrutura/inferência/adicionais, créditos elegíveis e margem; limitar campanha na aplicação e verificar cobrança final sem prometer hard cap do fornecedor |
| RNF21 | Portfólio público reproduzível | C | Todas; conclusão F8 | Revisar arquivos/histórico e artefatos exportados; nenhuma credencial ou informação privada é publicada; instruções, versões e exemplos permitem reproduzir a POC sem depender da máquina pessoal ou de instalação permanente |

## Avaliação suficiente para a POC

**Proposta P de faixas para medição:** pequena (até 2 mil tokens de diff), média (2–10 mil) e grande (acima de 10 mil ou maior que o contexto efetivo). Registrar também número de arquivos, hunks, linguagens, contexto adicional e volume de alterações entre componentes. Essas faixas organizam experimentos, não limitam a entrada nem aprovam metas.

Os exemplos anteriores de 2 s/3 min/5 min/10 jobs/2 por repositório/1 por PR permanecem em [decisions.md](decisions.md) apenas como histórico de propostas. Separar tempo de fila de tempo em execução e separar reviews ativas de chamadas simultâneas à GPU e às plataformas. Não extrapolar desempenho de PR pequena para grande.

Não é necessário fechar metas de produção para avançar. O aceite da POC é conseguir explicar e reproduzir o fluxo, demonstrar os controles que está aprendendo e registrar honestamente qualidade, tempo, custo e limitações. Ampliar testes somente quando um problema observado ou um novo objetivo de aprendizado justificar.
