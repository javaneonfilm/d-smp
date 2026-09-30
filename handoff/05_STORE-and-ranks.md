# Store, ranks and tickets

## The three products

| Product | Price | Length | Delivered by |
|---|---|---|---|
| **VIP** | €9.99 | 30 days | LuckPerms temporary group `vip` |
| **VIP+** | €19.99 | 30 days | LuckPerms temporary group `vipplus` |
| **Game Ticket** ("A game") | €4.99 | per game | Plugin command `cart ticket give` (see `01_PROMPT_plugin.md` HOST-4) |

Currency: **euro**. Prices on the website (`site/assets/app.js`, `SHOP` block) are display-only. The real charge is set in the store. Keep them identical.

## Store platform: CraftingStore

The owner **cannot use Tebex**. The chosen platform is **CraftingStore** (https://craftingstore.net), verified against its public docs on 2026-09-30:

- **Plans:** the free plan has unlimited packages and payments and includes a free `yourname.craftingstore.net` address. Silver (€8.99/month) adds instant processing and timed packages.
- **Fees:** CraftingStore takes no cut. The payment provider (PayPal, Stripe, Mollie…) takes theirs. The owner is responsible for tax/VAT and disputes.
- **Delivery speed:** free plan, payments are checked every 5 minutes (so 1–5 minutes). Silver, within seconds.
- **Payment accounts:** the owner connects their own PayPal (only an email is asked) or Stripe/Mollie account. These accounts need an adult account holder.
- **Package links:** each package page is `https://<store>.craftingstore.net/package/<id>`. It asks for the in-game name, then Add to cart.
- **New PayPal sellers:** payments can be held for review until seller history builds. CraftingStore's docs say to expect this.

### Setup checklist (owner, about 30 minutes)
1. Create the account at craftingstore.net.
2. Create a **Server** in the dashboard. Copy its plugin key. Install the CraftingStore plugin on the game server and put the key in its `config.yml`. Restart.
3. Pick the store address (free subdomain is fine to start).
4. Connect a payment provider in the payment methods page.
5. Set the store currency to **EUR**.
6. Create a category **"Ranks"** and three packages with the prices above.
7. Add commands to each package (below).
8. Test with **Payments → Manual Payment → "execute commands"**. Don't test with real money.
9. Copy the three package links and paste them into the website (`url:` fields in `SHOP`).
10. Fill in the store's Terms, Privacy and Impressum pages. Depending on the owner's country, the imprint may need real contact details. Check local rules.

### Package commands

Console commands run by CraftingStore. Use `{uuid_dashed}` because `{player}` strips non-alphanumeric characters, so a name with an underscore would break.

| Package | Initial | Chargeback |
|---|---|---|
| VIP | `lp user {uuid_dashed} parent addtemp vip 30d accumulate` | `lp user {uuid_dashed} parent remove vip` |
| VIP+ | `lp user {uuid_dashed} parent addtemp vipplus 30d accumulate` | `lp user {uuid_dashed} parent remove vipplus` |
| Game Ticket | `cart ticket give {uuid_dashed} 1 {transaction_id}` | `cart ticket revoke {uuid_dashed} 1 {transaction_id}` |

`accumulate` adds 30 days on top of any remaining time if the player buys again. Group names must match the real LuckPerms groups (Q2).

Optional: use CraftingStore's Discord integration to give buyers a Discord role.

## Warning: The website currently promises the wrong perks

The website's store cards were written **before** the plan was read. They don't match the plan:

| Item | Website says now | The plan says | Action |
|---|---|---|---|
| VIP | `[VIP]` tag, "Priority when the lobby is full", **1 exclusive cart skin**, VIP Discord channel | **A lot lower chance of being purged**; **wind charges** in the offhand (push only yourself) | Update the text |
| VIP+ | Everything in VIP, gold tag, **exclusive trail and kill effect**, "Top priority", **3 exclusive cart skins** | **Can't be purged at all**; **elytra + wind charges** in the offhand | Update the text |
| Game Ticket | "Your own private game", **"A staff host sets it up with you"**, "Pick the time and bring your friends" | The buyer is whitelisted, gets the **Host GUI** and sets everything up **themselves**; the webhook announces it | Update the text (self-serve) |

Cart skins and trails **don't exist** in the plan. Its cosmetics are kill effects, cart explosion effects and suffixes, all earned with coins.

### Suggested replacement perk text (once the plugin behaves this way)

**VIP** (€9.99 / 30 days)
- Much lower chance of being purged when the lobby is full
- Wind charges in the lobby
- `[VIP]` tag in chat and the tab list

**VIP+** (€19.99 / 30 days)
- Everything in VIP
- Can't be purged, so you always get in
- Elytra and wind charges in the lobby
- Gold `[VIP+]` tag

**Game Ticket** (€4.99 / game)
- Host your own game: you pick the mode, modifiers, squad size and player cap
- Set it up in-game right after buying, and Discord announces it
- Hoster rank for your game

Keep the "No pay-to-win" pledge accurate: VIP/VIP+ are **access and lobby perks** only (D17).

## Legal and platform notes (owner, not legal advice)
- Mojang's Commercial Usage Guidelines: don't sell anything that gives a gameplay advantage. Purge priority is an *access* perk and is the closest to the line, so review it.
- If selling to EU customers, check consumer rules for digital goods: withdrawal rights, clear prices including VAT, and an imprint. Ask a local adult or advisor.
- Write a refund and chargeback policy. Suggested rule: chargebacks mean a permanent ban and the perks are removed (the chargeback commands above do that).
- Payment providers can hold new-seller funds. Plan cash flow accordingly.
