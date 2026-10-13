# Sonos test fixtures

Real SOAP exchanges and UPnP event notifications captured from a Sonos Move 2
(firmware 93.1-73290, S2). They are inputs for the unit tests of the Sonos
protocol layer, so the parsers are exercised against what a speaker actually
sends and not against hand-written XML.

- `soap/<Service>.<Action>.<ok|err>.json`: one request/response pair per action
  (`request_body`, `response_body`, `http_status`, `error_code`).
- `notify/*.xml`: raw NOTIFY bodies. `AVTransport_0` and `RenderingControl_0`
  are the initial full-state events (`SEQ 0`); the others are partial deltas.

Sanitized: LAN addresses, the speaker UUID and the file-server tokens were
replaced with documentation-range values.
