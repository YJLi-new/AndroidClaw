# PLANv14 - Site redesign (androidclaw.top)

Status: active — line `site` dispatched (2026-11-03)
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
- Out of scope: app code, and publishing a GitHub Release. Releases need a
  separate decision by the user (see Survey).

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

## Running ledger

- 2026-11-03: Survey above. Skill cloned (713eef5, 3.6 MB, stdlib-only
  Python, writes only with `--persist`). PLANv14 written; line `site`
  dispatched.
