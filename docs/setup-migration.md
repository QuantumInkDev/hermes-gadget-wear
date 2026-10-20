# Move setup between phones

Open **Settings → Move setup** in the phone companion. This development feature offers
an address-only setup code and an encrypted configuration archive. Final fresh-install
and physical-device acceptance remains pending; this is not a release announcement.

## Share one connection

1. Choose **Show a setup code**, then a saved connection or box.
2. On the receiving phone, choose **Read a code from a photo** or **Read a code from a file**.
   Use a camera app first if needed; Quantum Relay does not request camera access here.
3. Review the name, scheme, host, port, path and full address. A code expires after ten
   minutes and does not prove who created it.
4. For a catalog box, enter its catalog-only credential on the receiving phone. For a
   cleartext watch connection, explicitly confirm your LAN/tailnet and traffic warning.
5. Choose **Add reviewed address**. Adding saves metadata; connection, discovery and pairing
   remain separate deliberate actions. A successfully added code cannot be replayed there.

The code contains one address and name. It does not contain an access key or authorize
the receiving phone or watch. Saving the code as a picture exposes its address to anyone
who can view that picture.

## Export configuration

Choose **Export configuration**, review the counts and any omitted items, enter and
confirm a passphrase of at least twelve characters, then choose where to save the
`.hgmigr` file. Keep its passphrase separately; the app does not store or recover it.

The export includes supported connection and box metadata, bots and ordering, profile
pictures/GIFs, quick actions, home screen widget layouts and the phone app appearance.
It contains private addresses, prompts and artwork, so both the file and passphrase
are needed to read it. One export supports at most 128 widget layouts; the confirmation
screen reports any omitted layouts or unsupported entries.

It excludes access keys, catalog credentials, phone/watch identities and pairing,
speech-provider credentials, cleartext-network consent, conversations, tasks,
recordings, approvals, receipts, watch appearance and launcher widget placement.
This is configuration transfer, not a conversation backup or device enrollment backup.

## Import and review

1. Choose **Import configuration**, select the complete archive and enter its passphrase.
2. Review the preview before confirming. Opening a file does not change setup.
3. Keep existing entries by default, or deliberately choose an imported value for each
   collision. Check quick-action recipients after remapping a connection or box.
4. Supply credentials for newly imported boxes. Cleartext endpoints require fresh
   LAN/tailnet consent; consent is not copied from the sending phone.
5. Confirm the selected changes. If capacity or validation fails, resolve the reported
   problem rather than expecting a partial import.
6. Check the result. A saved local import and propagation to the watch/launcher are
   reported separately. **Retry sync** retries propagation of saved setup; it does not
   re-import the archive.

Existing enrollment and credentials on the receiving phone remain local. A new phone or
watch still needs its normal pairing approval. Imported widget layouts are templates:
add a widget through the launcher and select its layout and bots. Android does not let
the archive restore another launcher's widget IDs or positions.

## Interrupted import or unreadable file

The app journals local import changes and attempts recovery before further setup
mutations. An unreadable recovery record pauses configuration changes. The explicit
**Remove unreadable record** option keeps current values, which may contain only part
of the interrupted import; it does not promise a rollback.

An archive-open failure can mean an incorrect passphrase, incomplete/damaged file,
unsupported format or storage read error. Check the original file and passphrase before
trying again. Closing a preview or cancelling a picker does not approve an import.
