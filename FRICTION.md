# Friction log

Problems I ran into while building Sponsor Scout for the Alexa+ track, September 2026.
Severity: **High** blocked the task, **Medium** cost real time or needed a workaround, **Low** was a papercut.

## 1. Connecting the MCP server to real Alexa+

**Severity:** High

**Task:** Connect Sponsor Scout's MCP server to Alexa+ and try it in the web simulator.

**Steps:** Read the Alexa+ MCP Toolkit docs ("Create an MCP Add-on" and "Set Up Your Development Environment").
Setup needs an Alexa developer account, an AWS account, and an IAM user that assumes
`arn:aws:iam::372468808636:role/AddOn3PDeveloperToolsRead` to reach the CLI packages. The page says to use "the AWS
account that you provided to the Alexa Solutions Architect", and the July 2026 launch post says the integration paths
are in Preview.

**Expected:** A way for a registered hackathon entrant to install the CLI, point an add-on at a tunnel URL and test it
in the web simulator.

**Actual:** I have no Solutions Architect contact and found no sign-up form or waitlist, only the general support chat.
I stopped at the setup page and never reached the simulator.

**Workaround:** Built the simulated Alexa+ page that the rules allow. It reaches the same MCP server over Streamable HTTP
the way Alexa+ would, and I followed the toolkit's server requirements anyway: spec 2025-11-25, Streamable HTTP, tool
calls under 500 ms.

**Suggestion:** Give registered entrants simulator access for the length of the event (the registration form could ask
for an AWS account ID), or offer a self-serve simulator with a daily cap. If access stays closed, say so on the
hackathon page so people plan for the simulated path from day one.

## 2. Finding the Alexa+ docs from the hackathon page

**Severity:** Low

**Task:** Find out what Alexa+ expects from an MCP server.

**Steps:** Opened the hackathon Resources page. The Alexa+ links go to the MCP transport spec and the MCP Apps Agent
Skills page. I found the Alexa+ MCP Toolkit docs later through a web search.

**Expected:** Links to the toolkit overview, the quickstart and the MCP Design Guide.

**Actual:** None of them were linked, so I learned about the 500 ms latency rule and the MCP Apps path for visuals after
the server was already built.

**Workaround:** Web search.

**Suggestion:** Link the MCP Toolkit overview, "Create an MCP Add-on" and the MCP Design Guide in the Alexa+ track
section.

## 3. The CLI name on public npm

**Severity:** Medium (security)

**Task:** Install the Alexa AI CLI.

**Steps:** The setup page says the CLI installs with npm, and the quickstart runs `alexa-ai configure`,
`alexa-ai new mcp` and `alexa-ai deploy`. The real install goes through a private CodeArtifact registry. I checked the
public registry before installing anything: `@alexa-ai/addon-local-inspector`, named on the Local Inspector page,
returns 404, and the unscoped `alexa-ai` package is an unrelated project ("AI engine for the Alexa WhatsApp bot",
first published September 2026).

**Expected:** A clear note that the CLI isn't on public npm.

**Actual:** Someone who guesses `npm install -g alexa-ai` runs a stranger's code. I didn't install it.

**Workaround:** None needed. I stopped at the check.

**Suggestion:** Add one line to the setup page saying the CLI isn't on the public npm registry and the unscoped
`alexa-ai` package there isn't Amazon's.

## 4. The 500 ms latency requirement

**Severity:** Medium

**Task:** Meet the toolkit's performance requirement.

**Steps:** The quickstart says "Your MCP server must meet a round-trip query response latency of less than 500 ms." I
timed every tool over Streamable HTTP. Sponsor searches took 2 to 3 seconds and job searches 3 to 6 seconds.

**Expected:** What exactly is measured (one tools/call? from which region? which percentile?) and whether the
simulator enforces it or only certification checks it.

**Actual:** One sentence. The Local Inspector reports latency, but its package isn't on public npm, so I couldn't run it.

**Workaround:** Measured it myself with a short script, moved title search to an SQLite FTS5 index and cached employer
records. Most calls now take 15 to 400 ms. The broadest sponsor search (software engineers, nationwide) takes about
750 ms, and the first job search after a restart can take over 10 seconds while it reads postings it hasn't seen yet.

**Suggestion:** Say what the 500 ms covers and at which percentile, and what Alexa+ does when a tool is slower (waits,
retries or tells the user). A latency check in a public tool would help too.

## 5. Structured tool results through the Spring AI MCP client

**Severity:** Low

**Task:** Draw cards on the voice page from the same tool results the agent reads.

**Steps:** Wrapped the MCP tools as Spring AI tool callbacks with `SyncMcpToolCallbackProvider` (Spring AI 2.0.1, MCP
Java SDK 2.0.0).

**Expected:** Access to each tool's structured result.

**Actual:** The callback returns a JSON string of MCP content blocks, so the result gets parsed twice: once for the
blocks, then again for the JSON inside the text block.

**Workaround:** A small wrapper around each callback that keeps the result and does the double parse.

**Suggestion:** Expose the `CallToolResult`, or its `structuredContent`, next to the string the model sees.
