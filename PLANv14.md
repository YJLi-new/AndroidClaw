# PLANv14 - Site redesign (androidclaw.top)

Status: complete — redesign merged through PR #4 and live gpt-6-luna screenshots through PR #8; androidclaw.top serves both (2026-11-03)
Owner: main session (planning, visual review, merges, pushes) + one Opus subagent (execution)
Started: 2026-11-03
Supersedes: `PLANv13.md` (complete, merged through PR #2 and PR #3)

## User request (verbatim, 2026-11-03)

> 从头重新设计page的页面：https://androidclaw.top/
> 你可以使用：https://github.com/nextlevelbuilder/ui-ux-pro-max-skill/tree/main/.claude/skills/ui-ux-pro-max
> 还有，多用你的视觉

## Scope

- Redesign the GitHub Pages site in `site/` from scratch: information
  architecture, visual design, copy, screenshots and both languages.
  `pages.yml` deploys `site/` on every push to `main`, so the new site goes
  live only when the PR is merged, which needs the user's approval.
- Use the ui-ux-pro-max skill as a reference. It is cloned read-only at commit
  713eef5 into the session scratchpad (`scratchpad/uiux/`) and is not installed
  into `~/.claude`.
- "多用你的视觉": every layout decision is checked on rendered screenshots,
  first by the line and then by the main session.
- 2026-11-03 addition: "顺便也redesign一下repo里的README" — README.md (English)
  and a new README.zh-CN.md, same line, after the site (line plan Revision 1).
- 2026-11-03 addition (after the PR #4 merge): "page和readme上的图尽量换成gpt的实测图，而非offline demo"
  — retake the site and README screenshots with live OpenAI `gpt-6-luna`
  replies instead of the offline demo (line plan Revision 3).
- Out of scope: app code. The v0.3.0 GitHub Release was published separately
  at the user's request (ledger).

## Survey of the live site (2026-11-03, main session, browser pane)

- Length: 15,964 px at 1440×900 and 20,191 px at 390×844, about 18 and 24
  screens. The screens section alone is 8,594 px. Every feature gets a full
  viewport with one large phone, and a second gallery repeats all 16 zh shots.
- The hero is blank at first paint on mobile: the content waits for an entrance
  animation.
- Fonts load from fonts.googleapis.com (Fraunces, Geist, JetBrains Mono). Those
  requests are unreliable from mainland China, which is half of the audience
  (EN + 简体中文).
- Screenshots come from the debug FakeProvider. Every shot shows the "Offline
  demo mode is active" banner, and some show image UUIDs and `[search:…]`
  markers.
- The tools list is set in tiny mono chips, and the mono labels are grey on
  near-black at low contrast.
- The download is stale. "Download APK" links to
  `releases/latest/download/AndroidClaw-latest.apk`, but the latest GitHub
  Release is `v0.1.0-qa3` (2026-05-01, 2,314,924 B). There is no v0.2.0 or
  v0.3.0 Release, so the button installs an app without the 0.2 and 0.3
  features.
- README links 34 shots under `site/assets/screens/{en,zh}/*.png`. Those paths
  must keep working.
- Facts for copy: v0.3.0 (versionCode 3), minSdk 26 (Android 8.0), release APK
  3,232,675 B, 37 model-facing tools, four runtime contracts, Apache-2.0 (`LICENSE`), logo colours
  `#78C92A` / `#21313A`.

## Target end state (acceptance)

1. **Structure:**
   - English at `/`, 简体中文 at `/zh/`, sharing one stylesheet and one small script.
   - `hreflang` links on both pages, and a language switch in the header.
   - No auto-redirect.
2. **Content order:**
   1. header;
   2. hero with download and GitHub CTAs plus a real phone screenshot;
   3. verified numbers;
   4. what's new in 0.3 (photos, web search, image generation and editing, video, voice);
   5. the four contracts;
   6. a screens gallery (one horizontal scroll-snap row with buttons and keyboard support, no autoplay);
   7. the 37 tools, grouped and readable;
   8. get started (install steps plus a build-from-source command with a copy button);
   9. footer.
3. **Visual direction (main session decision):**
   - Light first, with automatic dark mode through `prefers-color-scheme`.
   - Brand green `#78C92A` used sparingly; for text on light backgrounds, a darker green that passes 4.5:1.
   - Calm editorial-tech feel: plenty of whitespace, one accent, flat surfaces, at most one subtle shadow level for the phone frames.
   - System font stacks for Latin and CJK; at most one self-hosted Latin display font, ≤ 40 KB, OFL, licence included.
   - No requests to any third-party host.
4. **Length:** at most about 7,500 px at 1440 wide and about 10,000 px at 390 wide.
5. **Performance:**
   - Content visible at first paint, with no JS and no animation needed.
   - Every `<img>` has width, height and alt.
   - Below-the-fold images are lazy.
   - HTML + CSS + JS per page ≤ 60 KB uncompressed, not counting images.
   - Image weight at first view ≤ 400 KB.
6. **Accessibility:**
   - Contrast 4.5:1 for body text and 3:1 for large text and UI, in both themes.
   - Visible focus.
   - Targets at least 44×44 px.
   - `prefers-reduced-motion` respected.
   - No horizontal scroll from 320 px up.
7. **Screenshots:**
   - The page uses shots retaken on the AVD with the demo banner dismissed and no UUIDs in view.
   - Shots are written to the same `site/assets/screens/{en,zh}/` paths, so README stays consistent.
   - Web-optimised copies (WebP when practical) live in a separate folder used only by the site.
8. **Social and SEO:**
   - An OG image (1200×630) rendered from HTML.
   - Title, description and canonical on both pages.
   - `sitemap.xml` with both URLs.
   - `CNAME` and `robots.txt` kept.
9. **Honest download:** the CTA keeps the stable `releases/latest/download/AndroidClaw-latest.apk` URL with no version number on the button. A small "All releases" link sits beside it.

## Execution layout

- Line `site`: one Opus subagent in worktree `E:\OPC\AndroidClaw-wt\site`,
  branch `rework/site14` from `main` 7ecb238. It owns the AVD for this line.
  The plan is in `notes/site14_plan.md`.
- The main session reviews the handback in the browser pane at three widths,
  in both themes and both languages. Revisions go back to the same agent, each
  recorded first as "Revision N" in the line plan.
- After that: PR to `main`, merged only with the user's approval.

## Decisions

- 2026-11-03: Light first with auto dark. The app screenshots are light
  Material 3, so a light page shows them without a hard frame.
- 2026-11-03: No Google Fonts or other third-party hosts, so the site loads
  in mainland China. It uses system fonts, plus at most one small self-hosted
  display font.
- 2026-11-03: Separate `/zh/` page instead of a second half of one page.
- 2026-11-03: The skill's generated palette (slate and gold, Poppins and Open
  Sans) does not fit the brand and is not used. Its structure (device hero,
  screenshot strip, repeated download CTA, install command) and its
  pre-delivery checklist are used.
- 2026-11-03: For the live screenshots, only the user types the OpenAI key,
  on the emulator. Agents never read, type or inject it, and never reinstall
  or clear the app once it is in. Replies are shown exactly as the model
  wrote them.

## Running ledger

- 2026-11-03: Survey above. Skill cloned (713eef5, 3.6 MB, stdlib-only
  Python, writes only with `--persist`). PLANv14 written; line `site`
  dispatched.
- 2026-11-03: User: "做一下最新的apk然后发到release里". Release `v0.3.0` published
  (https://github.com/YJLi-new/AndroidClaw/releases/tag/v0.3.0, marked Latest),
  lightweight tag at 7ecb238 like the earlier tags. Assets:
  `AndroidClaw-v0.3.0.apk` and `AndroidClaw-latest.apk`, the same file.
  - Build: `qa`, clean, no build cache, 8m44s; 3,244,963 B; SHA-256
    c5459b96…312a77e4.
  - Package: `ai.androidclaw.app`, versionCode 3, versionName 0.3.0;
    signature scheme v2.
  - Signer: same certificate as `v0.1.0-qa3` (SHA-256 79e3578c…9332c1), so it
    installs as an update. Checked by certificate, not by an actual install
    over qa3.
  - AVD smoke: install OK, launch 789 ms, 0 crash lines.
  - The stable `releases/latest/download/AndroidClaw-latest.apk` URL now
    serves this exact file (byte compare).
  - The Survey's stale-download finding is resolved.
- 2026-11-03: Line `site` handback e133df8, then Revision 2 at 4f4b958, after
  the main session's visual review of the sliced renders (both widths, both
  themes, both languages).
  - Page heights: at 1440, EN 5,962 / ZH 5,846 px; at 390, EN 9,214 /
    ZH 8,547 px (was 15,964 / 20,191).
  - No horizontal overflow from 320 to 1440.
  - Weight: HTML + CSS + JS about 37.5 KB per page; first-view images
    36.9 KB (390) and 63.7 KB (1440).
  - 0 third-party requests; 0 images without size or alt; 0 broken links.
  - Lowest text contrast: light 5.02, dark 6.38.
  - Screenshots: 20 retaken, 8 kept from before.
  - README: 400 lines EN plus 281 lines in the new README.zh-CN.md (was 510
    combined). The local GitHub-style render is 4,647 px at 1280 (was
    16,060).
  - Revision 2: `photo_reply`, `web_sources` and `read_aloud` were re-shot as
    separate scenarios, because one lake conversation had appeared four
    times. The hero now starts on a whole element.
  - App bug found and left out of scope: `TaskRuntimeExecutor.kt:85` builds
    "Task … completed in isolated session" in English, and it shows in the
    ZH transcript.
- 2026-11-03: User: "通过后合并" (PR #4).
  - First CI run 41594794274 on 15e24d2: `fast` failed on one unit test,
    `ChatViewModelTest > draft input is bounded before it reaches Compose
    state` (`UnsupportedOperationException` at
    `TestCoroutineDispatchers.kt:106`); 622 tests, 1 failed, 8 skipped.
    `packaging` and `security-hardening` passed.
  - The PR changes no app code (only `site/`, the two READMEs and this
    plan), and the same test passed on the last push run on `main`
    (7ecb238). The failure is an intermittent test, not this line. The
    failed job was rerun once and passed (`fast` 6 min); all three jobs
    green.
  - Merged with `--merge --match-head-commit 15e24d2`: merge commit 1a55560
    on `origin/main`. The Pages run 41707615002 succeeded.
  - Live check: `/` and `/zh/` and `site.css` on androidclaw.top are
    byte-identical to the repo. Fresh headless renders of the live site
    (EN 1440 light, EN 375 dark, ZH 1440 dark, ZH 375 light) were checked
    by eye; heights at 1440 are 5,962 / 5,846 px as reviewed. Both pages:
    11 images, 0 broken, 0 third-party hosts, canonical and `lang` correct,
    Download APK points at the stable latest URL.
  - Follow-ups outside this plan: the intermittent `ChatViewModelTest`
    case; the ZH hero shot still shows the English "completed in isolated
    session" line until the app fix (PR #5) is merged and the shot is
    retaken.
- 2026-11-03: Live screenshots (line plan Revisions 3–5; agent `shots`).
  - The user typed the OpenAI key on the emulator. No agent read, typed or
    injected it.
  - Settings → Test connection always failed. The cause is an app bug, not
    the key: `SettingsViewModel` runs the blocking `generate()` on Main, so
    Android throws `NetworkOnMainThreadException`. The same key returned 200
    from the host. The fix is separate work (PR #6). Chat runs on IO and
    worked.
  - Retaken with real `gpt-6-luna` replies, 9 per language: `chat`,
    `tool_card`, `photo_reply`, `web_sources` (OpenAI hosted search),
    `read_aloud`, `whats_new`, `tasks`, `settings_web_search` and
    `generated_image` (a real `gpt-image` result in the viewer).
    `task_editor`, `skills` and `workspace` are kept: they show no demo text.
  - Rebuilt `og.png`, `og-zh.png` and the 4 README banners, because they
    showed the old demo transcript.
  - Live usage: 30 chat turns (EN 11, ZH 18, 1 failed); 9 of them put ZH
    prompts on the clipboard, because adb cannot type Chinese. 2 image
    generations; 0 video.
  - Checks:
    - 0 overflow from 320 to 1440; 0 third-party requests; 0 failed
      requests; 0 images without size or alt; 0 broken links.
    - Lowest contrast: light 5.02, dark 6.38.
    - Page heights at 1440: EN 5,962 / ZH 5,815. At 390: EN 9,242 /
      ZH 8,515.
    - First-view images: about 55 KB at 390 and 90–94 KB at 1440 (was 36.9 /
      63.7), because the real replies are denser.
  - Key scan of the diff: 0 key-like strings and 0 fragments of the real
    key. The settings shot shows an empty key field with "A key is saved".
  - Main-session review shot by shot: all pass. Two edge flaws are kept,
    because re-scrolling would only trade them for the jump-to-latest button:
    - the ZH hero's bottom edge cuts a speaker icon;
    - the EN `tool_card` has a thin bubble sliver at the top.
  - App follow-ups seen in the shots:
    - task run history shows raw Markdown (`**100%**`);
    - tool result summaries are English in the ZH UI;
    - session subtitles repeat the title;
    - image replies show a raw attachment link.
- 2026-11-03: App fix outside the site scope, found in the new zh screenshots: the isolated-session completion line (`TaskRuntimeExecutor`) was hardcoded English; it now comes from `task_isolated_completed` (en + zh-CN) in the app language at delivery time, like the video job messages (branch `claude/elated-dubinsky-dc03e3`).
- 2026-11-03: User: "通过后合并" (PR #8).
  - CI run 41719307222 on e14d608: `fast`, `packaging` and
    `security-hardening` all passed; the PR was MERGEABLE / CLEAN.
  - Merged with `--merge --match-head-commit e14d608`: merge commit b0cc10f.
  - Pages run 41720747871 succeeded.
  - Live check:
    - `/`, `/zh/` and `site.css` are byte-identical to the repo, and so are
      the spot-checked `img/en/chat.webp`, `img/zh/chat.webp` and `og.png`.
    - Fresh headless renders (EN 1440 light, EN 375 dark, ZH 1440 dark,
      ZH 375 light) were checked by eye: the hero, What's-new phone and
      gallery show the live gpt-6-luna shots.
    - Heights at 1440: EN 5,962 / ZH 5,815 px.
