# ADT MCP Write Extension

*🇧🇷 Português · [🇬🇧 English](README.md)*

Uma extensão do Eclipse que acrescenta **escrita de código-fonte ABAP** ao servidor
MCP das ABAP Development Tools (ADT), para que um agente de IA possa gravar e
ativar objetos no sistema SAP em vez de só ler.

Nenhuma credencial nova entra em jogo: a extensão roda **dentro do Eclipse** e usa
a sessão do ADT que já está autenticada no destino. Não há usuário e senha SAP em
arquivo de configuração, nem um processo externo guardando token.

> Projeto independente, sem vínculo com a SAP SE. SAP, ABAP e S/4HANA são marcas
> da SAP SE.

## Por que existe

O ADT 3.60+ já traz um servidor MCP (`Window > Preferences > ABAP Development >
MCP Server`). Ele expõe ferramentas de leitura, busca e — em versões recentes —
de **criação** de objeto, ativação, transporte e ATC.

O que não existe é gravar fonte. A própria ferramenta nativa de criação avisa:
*"This should not have the source content of object"*. Ela cria a casca; o
conteúdo continua sendo trabalho manual de copiar e colar no editor.

Esta extensão fecha essa lacuna, com duas ferramentas MCP.

### Relação com o ARC-1

A [ARC-1 MCP Extension](https://github.com/arc-mcp/arc1-adt-abap-mcp-ext) (MIT)
é a extensão que dá ao servidor MCP do ADT as ferramentas de leitura que fazem o
trabalho do dia a dia: busca no repositório, leitura de fonte, where-used, check
de sintaxe, ATC, data preview via HTTP genérico. Se você ainda não a tem, comece
por ela — **este projeto é complemento, não substituto**.

As 18 ferramentas do ARC-1 são **read-only por decisão de design**: nenhuma cria,
altera ou apaga fonte, e não há PUT nem lock/unlock no código dela. Esta extensão
existe justamente para quem precisa do passo seguinte, e mantém a separação: ler,
buscar e validar continuam lá; gravar e ativar ficam aqui, com as travas da seção
[Travas](#travas). As duas convivem no mesmo `dropins/` e no mesmo servidor MCP.

## Ferramentas

### `adt_write_source`

Substitui o fonte completo de um objeto existente e ativa.

| Parâmetro | Obrigatório | Descrição |
|---|---|---|
| `objectUri` | sim | URI ADT do objeto, sem query string. Ex.: `/sap/bc/adt/oo/classes/zcl_exemplo` |
| `source` | sim | Fonte completo que substitui o atual |
| `sourceUri` | não | Quando o fonte não é `objectUri + /source/main` (ex.: `.../includes/implementations`) |
| `transport` | não | Ordem de transporte. Vazio usa a que o SAP sugerir no LOCK (`CORRNR`) |
| `activate` | não | Ativar após gravar. Padrão `true` |
| `destination` | não | Destino ADT. Vazio usa o configurado no `eclipse.ini` |

Resposta:

```json
{"written":true,"activated":true,"destination":"DEV_100_dev_en",
 "objectUri":"/sap/bc/adt/oo/classes/zcl_exemplo",
 "sourceUri":"/sap/bc/adt/oo/classes/zcl_exemplo/source/main",
 "transport":"DEVK900123","messages":[]}
```

`written:true` com `activated:false` significa fonte gravado e objeto inativo — o
motivo está em `messages`, com o `href` apontando linha e coluna. A ferramenta
sinaliza `isError` nesse caso.

### `adt_activate`

Ativa um objeto já gravado, sem tocar no fonte. Existe para quando a gravação
passou e só a ativação falhou: reenviar o fonte inteiro só para ativar seria pior.
Parâmetros: `objectUri` (obrigatório) e `destination`.

## Instalação

Requisitos: Eclipse com **ADT 3.60.x** (a faixa declarada no manifesto é
`[3.60.0,4.0.0)`) e o servidor MCP do ADT ligado.

```bash
./build.sh --install       # Linux, macOS, Git Bash
```

```powershell
.\build.ps1 -Install       # Windows
```

Feche o Eclipse antes: a pasta `dropins/` só é lida no start. Depois reconecte o
cliente MCP — as ferramentas são registradas quando o servidor inicia.

Se a detecção automática do Eclipse falhar, passe o caminho:
`ECLIPSE_HOME=/opt/eclipse ./build.sh` ou `.\build.ps1 -EclipseHome C:\eclipse`.

Para desinstalar, apague o JAR de `<eclipse>/dropins/` e reinicie.

### Configuração

| Propriedade (`-D` no `eclipse.ini`) | Para que serve |
|---|---|
| `adt.mcp.destination` | Destino ADT padrão, para não repeti-lo em toda chamada |
| `adt.mcp.write.blockedDestinations` | Regex de destinos onde a escrita é recusada. Padrão: nome contendo `PRD` ou `PROD`. Vazio desliga |

O destino também é lido de `arc1.mcp.destination`, a propriedade que o
[ARC-1](https://github.com/arc-mcp/arc1-adt-abap-mcp-ext) já usa — quem tem as
duas extensões instaladas configura o destino uma vez só.

## Travas

Ficam no código da extensão, não no prompt do agente: uma regra que só existe no
contexto some junto com o contexto.

- **Objeto standard.** Recusa qualquer objeto fora de `Z*`, `Y*` ou namespace
  registrado (`/ABC/...`). A checagem é pelo nome porque, antes do lock, é o único
  dado disponível — e o lock já seria uma escrita no sistema.
- **Destino produtivo.** Recusa destino que case com `adt.mcp.write.blockedDestinations`.
- **`objectUri` com query string** é recusado: os parâmetros de controle
  (`_action`, `lockHandle`, `corrNr`) são montados pela extensão.

## O que aprendemos construindo isto

Estas são as armadilhas que custaram tempo. Estão aqui porque não achei nenhuma
delas documentada de forma direta.

1. **Sessão stateful é obrigatória.** O lock handle devolvido por `_action=LOCK`
   só vale dentro da mesma sessão que executa o PUT. Usando a
   `IStatefulSystemSession` da API de comunicação do ADT, isso já vem resolvido —
   não foi preciso mandar `X-sap-adt-sessiontype: stateful` na mão.

2. **A ativação vem depois do UNLOCK.** Com o objeto ainda travado, o servidor
   recusa a ativação com `403 Usuário <x> já está processando <objeto>`. A
   mensagem sugere conflito com outra pessoa ou com o editor aberto, mas é o
   **próprio lock da sua sessão** que barra — confirmado testando num objeto que
   nunca tinha sido aberto no editor. Ordem correta: LOCK → PUT → UNLOCK →
   ativação, com o UNLOCK em `finally`.

3. **Uma ferramenta, uma operação inteira** — não `lock`/`write`/`activate`/`unlock`
   separados. Um agente que perde o fio no meio da sequência deixa o objeto
   travado, e ninguém além dele tem o handle para destravar.

4. **HTTP 200 na ativação não é objeto ativo.** A resposta traz um checklist de
   mensagens; só `E`/`A`/`X` reprovam, `W` não. Por isso "gravado mas inativo" é
   um estado distinto de "gravado e ativo" na resposta da ferramenta.

5. **Compile com o ecj, não com o `javac`.** Os bundles do ADT são classfiles
   Java 21; um `javac` mais antigo recusa lê-los com `class file has wrong version
   65.0`. O ecj vem com o Eclipse e lê qualquer versão — e com ele não é preciso
   montar target platform de PDE: basta apontar o classpath para os JARs que já
   estão instalados.

6. **Em ABAP Cloud, tipo standard não liberado reprova na ativação** (`INT4_TABLE`,
   por exemplo). Vale declarar tipos próprios no código que o agente gera.

## Build e testes

```bash
./build.sh          # gera build/<bundle>.jar
./selftest.ps1      # ou: rode SelfTest pela sua IDE
```

O autoteste não fala com o SAP: cobre o parser de argumentos JSON (incluindo fonte
ABAP com aspas e quebras de linha passando por escape e voltando), as travas de
namespace e de destino, e a leitura das respostas de LOCK, de ativação e de erro.

## Limites conhecidos

- **Só edita objeto existente.** Para criar, use as ferramentas nativas do ADT MCP
  (`..._creation-get_all_creatable_objects` → `get_object_type_details` →
  `run_validation` → `create_object`) e então grave o fonte com esta extensão.
- **Pacote (`DEVC`) não está entre os tipos criáveis** do MCP nativo: vai por POST
  em `/sap/bc/adt/packages` com `application/vnd.sap.adt.packages.v2+xml` (v1 e v3
  devolvem 406). O XML precisa de `adtcore:responsible` preenchido e dos elementos
  `pak:useAccesses`, `pak:packageInterfaces` e `pak:subPackages`, mesmo vazios.
- **Ativação em massa** não está implementada: uma chamada, um objeto.
- **Nada de criar objeto novo** a partir desta extensão — é decisão de escopo, não
  limitação técnica.

## Licença

MIT. Veja [LICENSE](LICENSE).
