# OpenCode Harness – UI mockups

OpenCode desktop layout ported to Material 3. Colors are placeholder tokens; swap in the extracted opencode theme in Phase 0.

1 · Projects

9:41LTE 82%

## Projectsopencode 2.x · server ready

**OpenRouter** · qwen3-coder

Connected

**wordwise**

/workspace/wordwise

Fix accessibility service crash · 12 min ago

**Retry logic for downloads** quick

/workspace/retry-logic-downloads

Title written by AI · yesterday

**atombrowser** shortcut

/sdcard/Dev/atombrowser

Linked from Files · 3 sessions

Projects

Chat

Files

Terminal

Settings

2 · Chat (streaming, tools, permission)

9:41LTE 82%

## Fix service crash

wordwise

buildqwen3-coder41% ctx

WordWise crashes when the accessibility service restarts. Find out why.

I'll start with the service class and its lifecycle.

read WordWiseService.kt0.2s

grep "onServiceConnected"3 matches

The client is created in `onCreate` but used after `onUnbind`, so a restart leaves a null reference.

edit WordWiseService.kt+4 −2

− client = GeminiClient()\
\+ client = client ?: GeminiClient()

shell ./gradlew assembleDebugrunning

**crash-trace.png**

0:12

Build is running

**Allow shell command?**

```
./gradlew assembleDebug
```

Message, / for commands, @ for files

2b · Slash commands and pasted images

9:41LTE 82%

## Retry logic for downloads

retry-logic-downloads

Add retry with backoff. Here is the error.

Commands

**/new**New session

**/models**Switch model

**/compact**Summarize to free context

**/undo**Revert last message and edits

**/redo**Restore reverted message

**/share**Share session link

Custom and skills

**/review**Review staged changes*custom*

**/frontend-design**Design guidance*skill*

**/context7**Look up library docs*mcp*

/

build ▾qwen3-coder ▾default ▾

4 · Files (long-press a folder)

9:41LTE 82%

## Files/workspace

WorkspaceDownloadsPhone storage

**wordwise**14 items · git · 2 chats

**retry-logic-downloads**8 items · 1 chat

**swift-turing**3 items

**notes.md**2.1 KB · yesterday

**build.log**48 KB · 3 min ago

Rename

Copy

Move

Open in chat

Open as project

Info

---

Delete

Shortcut added to Projects

**View**

Projects

Chat

Files

Terminal

Settings

3 · Changes (latest turn)

9:41LTE 82%

## Changes2 files · +9 −3

WordWiseService.kt

41 override fun onServiceConnected() {

−42 client = GeminiClient()

+42 client = client ?: GeminiClient()

+43 registerReceiver(restartReceiver, filter)

44 }

RestartReceiver.kt

\+ 1 class RestartReceiver : BroadcastReceiver() {

\+ 2 override fun onReceive(c: Context, i: Intent) {

\+ 3 WordWiseService.instance?.reset()

\+ 4 }

\+ 5 }

**Keep these changes**

Or go back to before this turn.

Projects

Chat

Files

Terminal

Settings

4 · Terminal

9:41LTE 82%

## Terminal/workspace/wordwise · Alpine

root@harness:\~/wordwise# git status\
On branch main\
Changes not staged:\
modified: WordWiseService.kt\
new file: RestartReceiver.kt\
\
root@harness:\~/wordwise# apk add openjdk17\
(1/9) Installing java-common\
(2/9) Installing openjdk17-jre-headless\
root@harness:\~/wordwise#

**Esc****Ctrl****Alt****Tab****↑****↓****←****→**

Projects

Chat

Files

Terminal

Settings

6 · Settings (ported from OpenCode desktop)

9:41LTE 82%

## Settings

General

Shortcuts

Providers

Models

Appearance

Color schemeLight, dark or follow system

System ▾

ThemeColors used across the app

opencode ▾

FontCode and terminal text

Plex Mono ▾

Notifications

Agent finished

Permission requests

Errors

Sound effects

AgentPlays when a reply completes

Off ▾

Runtime

**Installing OpenCode**

Downloading 41 / 114 MB

Full storage accessOpen files in /sdcard

Run diagnosticsDNS, HTTPS and server checks

Projects

Chat

Files

Terminal

Settings