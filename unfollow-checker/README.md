# Follow-back Checker

Single-file web app (`index.html`) that shows which accounts you follow that don't follow you back, with a keep list for people you never want flagged.

- Open `index.html` in any browser. No server, no install, no login.
- Load the `followers_*.json` and `following.json` files from Instagram's "Download your information" export (JSON format).
- Add usernames to the keep list; they're saved in your browser's localStorage and excluded from results.
- Work through the list with "Open next profile", or export it as CSV/clipboard.

Unfollowing is left to you on purpose: automating it requires your Instagram password and violates Instagram's terms, and is a common cause of action blocks and bans.
