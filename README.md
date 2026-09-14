# ADT MCP Write Extension

*🇬🇧 English · [🇧🇷 Português](README.pt-BR.md)*

An Eclipse plugin that adds **ABAP source writing** to the MCP server built into
the ABAP Development Tools (ADT), so an AI agent can save and activate objects in
your SAP system instead of only reading them.

No new credential is involved: the plugin runs **inside Eclipse** and uses the ADT
session you have already authenticated against the destination. There is no SAP
username and password in a config file, and no external process holding a token.

> Independent project, not affiliated with SAP SE. SAP, ABAP and S/4HANA are
> trademarks of SAP SE.

## Why it exists

ADT 3.60+ ships with an MCP server (`Window > Preferences > ABAP Development >
MCP Server`). It exposes tools for reading, searching and — in recent versions —
for **creating** objects, activating, transports and ATC.

What it does not do is write source. SAP's own creation tool spells it out:
*"This should not have the source content of object"*. It creates the shell; the
content is still copy-and-paste work in the editor.

This plugin closes that gap, with two MCP tools.

### Relationship with ARC-1

The [ARC-1 MCP Extension](https://github.com/arc-mcp/arc1-adt-abap-mcp-ext) (MIT)
is what gives the ADT MCP server the read tools that carry the daily work:
repository search, source reading, where-used, syntax check, ATC, data preview
through generic HTTP. If you don't have it yet, start there — **this project
complements it, it does not replace it**.

ARC-1's 18 tools are **read-only by design**: none of them creates, changes or
deletes source, and there is no PUT and no lock/unlock in its code. This plugin
exists for the step after that, and keeps the split: reading, searching and
validating stay there; writing and activating live here, behind the
[Guard rails](#guard-rails). Both live in the same `dropins/` folder and serve the
same MCP server.

## Tools

### `adt_write_source`

Replaces the entire source of an existing object and activates it.

| Parameter | Required | Description |
|---|---|---|
| `objectUri` | yes | ADT URI of the object, no query string. E.g. `/sap/bc/adt/oo/classes/zcl_example` |
| `source` | yes | Complete source that replaces the current one |
| `sourceUri` | no | When the source is not `objectUri + /source/main` (e.g. `.../includes/implementations`) |
| `transport` | no | Transport request. Empty uses the one SAP suggests on LOCK (`CORRNR`) |
| `activate` | no | Activate after saving. Defaults to `true` |
| `destination` | no | ADT destination. Empty uses the one configured in `eclipse.ini` |

Response:

```json
{"written":true,"activated":true,"destination":"DEV_100_dev_en",
 "objectUri":"/sap/bc/adt/oo/classes/zcl_example",
 "sourceUri":"/sap/bc/adt/oo/classes/zcl_example/source/main",
 "transport":"DEVK900123","messages":[]}
```

`written:true` with `activated:false` means the source was saved and the object is
inactive — the reason is in `messages`, with `href` pointing at line and column.
The tool reports `isError` in that case.

### `adt_activate`

Activates an object that is already saved, without touching the source. It exists
for when the write succeeded and only activation failed: resending the whole
source just to activate would be worse. Parameters: `objectUri` (required) and
`destination`.

## Installation

Requirements: Eclipse with **ADT 3.60.x** (the manifest declares the range
`[3.60.0,4.0.0)`) and the ADT MCP server switched on.

```bash
./build.sh --install       # Linux, macOS, Git Bash
```

```powershell
.\build.ps1 -Install       # Windows
```

Close Eclipse first: the `dropins/` folder is only read at startup. Afterwards,
reconnect your MCP client — tools are registered when the server starts.

If Eclipse auto-detection fails, pass the path:
`ECLIPSE_HOME=/opt/eclipse ./build.sh` or `.\build.ps1 -EclipseHome C:\eclipse`.

To uninstall, delete the JAR from `<eclipse>/dropins/` and restart.

### Configuration

| Property (`-D` in `eclipse.ini`) | What it does |
|---|---|
| `adt.mcp.destination` | Default ADT destination, so you don't repeat it on every call |
| `adt.mcp.write.blockedDestinations` | Regex of destinations where writing is refused. Default: name containing `PRD` or `PROD`. Empty disables it |

The destination is also read from `arc1.mcp.destination`, the property
[ARC-1](https://github.com/arc-mcp/arc1-adt-abap-mcp-ext) already uses — with both
plugins installed you configure the destination once.

## Guard rails

They live in the plugin's code, not in the agent's prompt: a rule that only exists
in the context disappears along with the context.

- **Standard objects.** Refuses anything outside `Z*`, `Y*` or a registered
  namespace (`/ABC/...`). The check is by name because, before the lock, that is
  the only data available — and the lock would already be a write to the system.
- **Productive destinations.** Refuses any destination matching
  `adt.mcp.write.blockedDestinations`.
- **`objectUri` with a query string** is refused: the control parameters
  (`_action`, `lockHandle`, `corrNr`) are built by the plugin.

## What we learned building this

These are the traps that cost time. They are here because I couldn't find any of
them documented in a straightforward way.

1. **A stateful session is mandatory.** The lock handle returned by `_action=LOCK`
   is only valid inside the same session that performs the PUT. Using
   `IStatefulSystemSession` from the ADT communication API takes care of it —
   there was no need to send `X-sap-adt-sessiontype: stateful` by hand.

2. **Activation comes after UNLOCK.** With the object still locked, the server
   refuses activation with `403 User <x> is already processing <object>`. The
   message suggests a clash with another person or with your open editor, but it
   is **your own session's lock** that blocks it — confirmed by testing on an
   object that had never been opened in the editor. Correct order: LOCK → PUT →
   UNLOCK → activation, with the UNLOCK in a `finally`.

3. **One tool, one whole operation** — not separate `lock`/`write`/`activate`/`unlock`.
   An agent that loses the thread halfway through leaves the object locked, and
   nobody else holds the handle to release it.

4. **HTTP 200 on activation does not mean the object is active.** The response
   carries a checklist of messages; only `E`/`A`/`X` fail it, `W` does not. That
   is why "saved but inactive" is a distinct state from "saved and active" in the
   tool's response.

5. **Compile with ecj, not with `javac`.** The ADT bundles are Java 21 class
   files; an older `javac` refuses to read them with `class file has wrong version
   65.0`. ecj ships with Eclipse and reads any version — and with it you don't
   need a PDE target platform: just point the classpath at the JARs already
   installed.

6. **In ABAP Cloud, a non-released standard type fails activation** (`INT4_TABLE`,
   for example). Worth declaring your own types in the code the agent generates.

## Build and tests

```bash
./build.sh          # produces build/<bundle>.jar
./selftest.ps1      # or: run SelfTest from your IDE
```

The self-test never talks to SAP: it covers the JSON argument parser (including
ABAP source with quotes and line breaks going through escaping and back), the
namespace and destination guard rails, and the parsing of LOCK, activation and
error responses.

## Known limits

- **It only edits existing objects.** To create, use the native ADT MCP tools
  (`..._creation-get_all_creatable_objects` → `get_object_type_details` →
  `run_validation` → `create_object`) and then write the source with this plugin.
- **Packages (`DEVC`) are not among the creatable types** of the native MCP
  server: they go through a POST to `/sap/bc/adt/packages` with
  `application/vnd.sap.adt.packages.v2+xml` (v1 and v3 return 406). The XML needs
  `adtcore:responsible` filled in and the elements `pak:useAccesses`,
  `pak:packageInterfaces` and `pak:subPackages`, even when empty.
- **Mass activation** is not implemented: one call, one object.
- **No object creation** from this plugin — a scope decision, not a technical
  limitation.

## License

MIT. See [LICENSE](LICENSE).
