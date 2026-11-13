# Segurança e identidade

[Índice](../README.md) · [Arquitetura](architecture.md) · [Requisitos](requirements.md).

Direção solicitada: menor privilégio, autorização por chamada e isolamento de identidades. Mecanismos concretos abaixo são **P**, salvo as distinções explicitamente requeridas. A F7 amplia avaliações; as proteções necessárias a uma função devem existir quando ela for entregue.

## Identidades e credenciais por fronteira

| Fronteira | Identidade e mecanismo a planejar | Regra |
| --- | --- | --- |
| Usuário → aplicação/gateway | Sessão/OIDC de authorization server apropriado; Q de implantação | Validar emissor, destinatário, prazo e permissões; identidade não concede qualquer repositório automaticamente |
| Worker/agente → gateway | Identidade de workload e token destinado ao gateway; fluxo não interativo a escolher | Vincular job, conexão e escopo de recursos fora do controle do modelo |
| Gateway → MCP Server | Credencial específica da conexão, OAuth quando suportado, ou mecanismo aprovado do servidor | Não encaminhar indiscriminadamente o bearer recebido do agente |
| MCP Server/adaptador → plataforma Git | Instalação de App, token de serviço ou delegação compatível com a oferta | Restringir repositórios, operações e duração; observar revogação/suspensão |
| Aplicação → provedor/modelo | Credencial própria do provedor externo; controle de rede/proxy para Ollama local | Não reutilizar credenciais Git ou do usuário; não colocar credenciais no prompt |
| Plataforma → webhook | Verificação específica de autenticidade da entrega | Autenticidade do emissor não transforma código, descrição ou comentário em instrução confiável |

A especificação [MCP Authorization](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization) trata autorização para transporte HTTP; não é uma obrigação de usar o mesmo fluxo em `stdio`. Este usa credenciais do ambiente/processo conforme implementação. Em HTTP, usar authorization server apropriado, validação de recurso/audience e políticas por chamada. Não criar um IdP próprio.

Catálogo filtrado melhora exposição, mas nunca substitui enforcement. O gateway reavalia identidade, ferramenta, plataforma, conexão, repositório, SHA e orçamento a cada chamada. Aplicação e downstream também validam sua parte; o gateway não protege sozinho o fluxo inteiro. Referência: [MCP Security Best Practices](https://modelcontextprotocol.io/docs/2026-07-28/tutorials/security/security_best_practices).

## Automação GitHub proposta

Instalar previamente uma GitHub App nos repositórios selecionados. Instalação e autorização pessoal para atuar em nome do usuário são conceitos diferentes. A automação atua como bot da instalação, sem login a cada PR; abrir/atualizar PR na interface pode disparar webhook sem GitHub Actions.

O backend ou o MCP Server compatível obtém installation access token temporário usando a identidade da App, restringe recursos e o obtém novamente ao expirar. Não depende de refresh token pessoal. A documentação atual informa validade de uma hora; usar sempre o vencimento retornado, renovar com margem e impedir tentativas após revogação/suspensão. Fonte: [autenticação de instalação](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/authenticating-as-a-github-app-installation).

**P de permissões:** leitura de metadados, conteúdo e PR para revisão; escrita de resumo somente na identidade/caminho do publicador. A lista exata depende dos endpoints selecionados e será validada antes da F4. Não conceder escrita apenas porque o servidor dispõe da ferramenta. PAT e machine user são alternativas a avaliar, não padrão aprovado.

O servidor oficial documenta [App em `stdio`](https://github.com/github/github-mcp-server/blob/main/docs/github-app-auth.md); o modo HTTP tem condições distintas. Confirmar versão, instalação e suporte a renovação no modo escolhido. Não inferir que um login OAuth interativo do servidor remoto aceita automaticamente o mesmo token.

## Delegação interativa futura

Usar OAuth quando uma operação precisar realmente ocorrer em nome do usuário. Armazenar credenciais de forma segura, renovar somente quando o mecanismo suportar e a autorização continuar válida, e solicitar nova autorização quando necessário. Credencial usada no `git push` não é autorização do agente. Executar localmente não obriga usar identidade pessoal.

## Conteúdo não confiável e prompt injection

Código, comentários, strings, documentação, título/descrição de PR, mensagens de commit, discussões e respostas de ferramentas podem conter instruções adversariais. Elas podem influenciar o modelo apenas por serem lidas, sem execução de código.

Separar dois riscos: **manipulação da análise**, como esconder bug ou inventar aprovação; e **abuso de ferramentas**, como ler outro repositório ou divulgar código. Isolamento de instruções e detecção de padrões ajudam, mas nenhum filtro elimina prompt injection. Usar dados de PR como evidência, nunca como autoridade para trocar políticas, destinos ou credenciais.

| Ameaça | Controle proposto fora do modelo | Verificação planejada |
| --- | --- | --- |
| PR pede para ignorar bug | Prompt separa instruções de dados, validação com evidência e benchmark rotulado | Bug conhecido com instrução hostil continua avaliado; registrar falhas de detecção |
| Modelo pede outro repositório/SHA | Contexto autorizado imutável e comparação de argumentos no harness/gateway | Acesso negado e auditado em todas as combinações cruzadas |
| Traversal e caminhos ambíguos | Normalização conforme plataforma; rejeitar `..`, absoluto indevido, encoding ambíguo e symlink fora da política | Fixtures de caminhos adversariais falham antes de leitura |
| SSRF e destinos arbitrários | URLs/processos somente do cadastro administrativo; allowlist de egress; validar DNS e redirects | URL de metadados, loopback indevido, redirect externo e host não cadastrado são bloqueados |
| Token para recurso errado | Verificar assinatura/emissor/audience/prazo conforme mecanismo; credenciais distintas por salto | Token válido para outro recurso continua negado |
| Tool poisoning | Fixar servidor/versão e fingerprint de schema/descrição; revisar alterações antes de expor | Mudança de descrição/schema invalida aprovação ou suspende conexão |
| Explosão de ferramentas/contexto | Allowlist, schema, limites de bytes/tokens, chamadas e duração | Pedido excessivo termina com erro/relatório parcial, sem loop |
| Segredo em arquivo/diff | Detecção antes do envio, redaction ou bloqueio por política; evidência protegida | Segredo sintético não aparece em prompt externo, comentário ou log |
| Saída maliciosa | Schema e validação semântica; escape/renderização segura e controle de links/menções | HTML, links hostis, instruções e evidência inventada não viram comentário publicável |
| Replay e duplicação | Autenticar, deduplicar por entrega/conexão e reconciliar publicação | Redelivery e retry não criam spam de resumos |
| Saturação de GPU compartilhada | Fila limitada, slots, timeouts, janelas de benchmark e diagnóstico | Carga concorrente não vira fallback externo silencioso |

Não remover indiscriminadamente dados necessários à revisão: quando redaction ou bloqueio retirar evidência, relatar a cobertura afetada. Segredos nunca devem aparecer nos exemplos/documentos de configuração versionados.

## Webhooks

No GitHub, conferir HMAC do corpo original com o segredo da conexão e comparação em tempo constante, conforme [validação oficial](https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries). Validar tipo/ação, instalação, repositório, tamanho e ID de entrega; persistir deduplicação antes do ACK de aceitação. O formato não deve ser reaproveitado como se fosse igual em GitLab ou Bitbucket.

Para [GitLab](https://docs.gitlab.com/user/project/integrations/webhooks/) e [Bitbucket Cloud](https://support.atlassian.com/bitbucket-cloud/docs/manage-webhooks/), verificar os mecanismos da oferta/versão escolhida e implementar testes de corpo adulterado e credencial incorreta. Políticas de replay devem considerar o que a plataforma realmente fornece; não inventar timestamp assinado obrigatório quando não houver. TLS, segredo de conexão, deduplicação e reprocessamento administrativo têm papéis diferentes.

## Ollama e dados enviados

O endpoint observado responde localmente sem autenticação e a porta está publicada em `0.0.0.0` e `[::]`; isso não prova alcance externo, pois firewall/rede não foram testados. Não assumir proteção só por rodar em Docker. Antes de integrar, confirmar alcance permitido e definir firewall/rede restrita; se atravessar fronteira não confiável, usar proxy autenticado/TLS. Nenhuma dessas alterações foi feita nesta tarefa.

Somente a camada de modelos deve acessar destinos de inferência cadastrados. Modelo, PR e resultado de ferramenta não podem definir endpoint. A demonstração cloud usará uma API externa como destino principal explicitamente configurado; isso não habilita fallback automático quando o Ollama local falhar. Definir provedor, repositórios, classificação de dados, retenção, orçamento e registro de envio. A review com inferência local continua usando rede para plataformas Git e MCP Servers remotos.

## VPS temporária e portfólio público

As mesmas verificações de webhook, identidade, autorização, SHA e publicação se aplicam na VPS. **P:** expor apenas a entrada HTTPS necessária e acesso administrativo restrito; manter banco, gateway e MCP Servers internos sem portas públicas. Usar referências de segredos, credenciais da campanha com menor privilégio e limites de gasto/chamadas; não levar credenciais alheias do desktop para a demonstração.

Dados sintéticos/publicáveis são o padrão proposto de benchmark e gravação. Plano gratuito de API não equivale a autorização para enviar código privado: conferir retenção, treinamento/uso de dados, cotas e termos da oferta antes da escolha. Não conceder shell, ferramentas hospedadas ou acesso MCP direto do provedor apenas porque a API os oferece; as ferramentas da review continuam sob o gateway da aplicação.

Antes da publicação pública, revisar arquivos e histórico, licenças dos exemplos, caminhos pessoais, projetos privados, links de PR, faturas e telas da gravação. O inventário local atual contém detalhes de diagnóstico e precisa de versão sanitizada para divulgação. Não publicar `.env` real, chaves de App, tokens de modelo/cloud, prompts com código privado ou resultados que revelem dados restritos.

Exportar evidências sanitizadas antes de remover infraestrutura. Encerramento inclui inventário e exclusão dos recursos da POC, revogação de credenciais temporárias e conferência de cobrança residual; não afetar credenciais/volumes compartilhados de outros projetos. Procedimento e evidências estão em [cloud-demo-and-portfolio.md](cloud-demo-and-portfolio.md). Essas ações não são ferramentas do modelo nem foram executadas nesta tarefa.

## Logs, retenção e escopo inicial

**P para a POC:** logs simples com jobId, decisão de autorização, etapas e erros; não registrar tokens ou código privado por padrão. Guardar as evidências úteis ao portfólio e descartar temporários ao encerrar. Não exigir política de retenção por classe, backup contínuo, SIEM ou auditoria corporativa. Demonstrar uma chamada permitida e outra negada já torna o controle observável no exercício.

No MVP proposto: análise estática, sem executar código da PR, sem shell genérico e sem merge, aprovação automática ou alteração de código pelo modelo. O publicador determinístico só envia resultado validado e reconciliado. Testes adversariais medem tanto tentativas de abuso bloqueadas quanto degradação de qualidade; bloquear ferramentas não garante uma análise correta.
