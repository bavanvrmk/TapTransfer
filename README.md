<h1 align="center">TapTransfer</h1>

<p align="center"><strong>Tap two Android phones to share a UPI payment request.<br>Split trip expenses and keep one ledger for the whole group.</strong></p>

<p align="center">
  <img alt="Android 8.0 and newer" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84">
  <img alt="Written in Kotlin" src="https://img.shields.io/badge/Kotlin-Compose-7F52FF">
  <img alt="App version 1.7" src="https://img.shields.io/badge/version-1.7-2563EB">
</p>

<p align="center"><a href="#quick-start">Quick start</a> · <a href="#request-a-payment">Request a payment</a> · <a href="#manage-a-trip">Manage a trip</a> · <a href="#development">Development</a></p>

TapTransfer passes a payee’s UPI ID, amount, and note to the payer’s phone over NFC. The payer reviews the request and completes the transfer in their UPI app. An optional trip ledger lets a group record who paid, who owes what, and how much cash its wallet keeper holds.

> [!IMPORTANT]
> Check the recipient in your UPI app before authorizing payment. TapTransfer records requests and handoffs. Check the bank or UPI transaction history to verify that money arrived, especially before retrying.

## Quick start

Use JDK 17 and install Android SDK Platform 35, Build Tools 35.0.0, and Platform Tools. From Windows Command Prompt:

```bat
git clone https://github.com/bavanvrmk/TapTransfer.git
cd TapTransfer
gradlew.bat assembleDebug
```

The Gradle wrapper downloads Gradle 8.9 on its first run. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it on two NFC phones with host card emulation (HCE), running Android 8.0 or later. Enable NFC on both. The payer needs a UPI app that accepts personal UPI payment links.

## Request a payment

1. **Receiver** — Open **Home → Set up profile** and save your name and UPI ID. In **Receive**, enter an INR amount, add an optional note, and create the request.
2. **Payer** — Open **Pay** to start scanning. Keep both phones unlocked, leave the receiver on **Receive**, and bring the NFC antennas together.
3. **Payer** — Review the amount and recipient, open your UPI app, and authorize the payment there. If a tap is inconvenient, the receiver can share the QR code or payment link.

For repeat requests, save an amount preset or an amount-and-note template. **History** lets you search and share local activity. It keeps the last 100 records.

## Manage a trip

Enable **Trip money** in **Profile**, then open **Trips**. A trip works locally. To collaborate, the organizer connects Google and selects **Create shared trip**. An existing trip can be connected from **Sharing → Create shared Sheet**. Google Cloud configuration is required before the first shared trip. [Google account setup and two-account walkthrough](GOOGLE-SHEETS.md)

| What you want to do | Where to go |
| --- | --- |
| Record a purchase | **Add a bill → Already paid**. Choose the payer and payment method. Attach a photo or PDF receipt if needed. |
| Assign someone to pay later | **Add a bill → To pay**. Pick yourself or another member. Open the assignment from **Overview → To pay** to record payment or cancel it. |
| Split dinner or a mixed receipt | Start with an equal split, change the participants, or open advanced splits for exact shares, individual items, and multiple payers. |
| Track cash | Record contributions to the group wallet, spending by the keeper, and repayments made by hand. |
| Invite another member | In **Sharing**, invite their Google address as an editor. They open **Trips → Join a shared Sheet**, paste the link, and can add a shortcut to the same file in My Drive. |
| Export or remove a trip | Export CSV for review or a `.taptrip` copy with receipts for backup. Long-press a trip or tap **Actions** to delete its local copy. The confirmation offers Undo. |

Shared trips save entries locally first and sync with the organizer’s Sheet. Members can use **Sharing → Sync now** after reconnecting. For groups exchanging offline copies or running their own server, see the [trip sharing guide](trip-server/README.md).

## Development

| Component | Responsibility |
| --- | --- |
| [Android app](app/src/main/java/com/taptransfer) | Compose screens, NFC reader and HCE service, UPI handoff, and local activity |
| [Trip ledger](app/src/main/java/com/taptransfer/TripLedger.kt) | Bill allocations, wallet balances, corrections, and settlements |
| [Google Sheets integration](app/src/main/java/com/taptransfer/SharedSheet.kt) | Sheet initialization, editor invitations, and merging member entries |
| [Android tests](app/src/test/java/com/taptransfer) | Request validation, NFC protocol, UPI routing, accounting, shared-sheet conflicts, and trip gestures |

From the project root on Windows:

```bat
gradlew.bat testDebugUnitTest lintRelease
python -m unittest discover -s trip-server -p test_server.py
```

<details>
<summary><strong>NFC protocol v1</strong></summary>

| Command | Response |
| --- | --- |
| SELECT AID `F0010203040506` | ASCII `TT1` followed by status `9000` |
| GET CHUNK `80 10 offsetHi offsetLo 00` | Up to 180 payload bytes followed by `6100` for more or `9000` for complete |

The UTF-8 JSON payload contains `v`, `id`, `upi`, `name`, `amount`, `note`, and `expires`, with a 2,048-byte limit. TapTransfer enforces a 120-second expiry. External UPI apps control how they handle exported links. Receiver names and UPI IDs are supplied by the sender of the request.

</details>

<details>
<summary><strong>Physical-device acceptance checks</strong></summary>

- Swap sender and receiver roles and repeat 20 taps in each direction. Try an interrupted tap, an expired request, a locked screen, and disabled NFC.
- Exercise QR sharing, UPI app cancellation, and network loss after handoff. Verify the result in the bank before retrying.
- Create a shared trip with two Google accounts, submit a bill from each phone, reconnect, and compare both balances and receipts.
- Check TalkBack, large text, rotation, and trip deletion with Undo.

</details>

The project builds a debug APK. Public distribution still requires production signing and release testing, including the physical-device and Google account checks above.
