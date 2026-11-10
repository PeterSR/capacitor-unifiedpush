# Security Policy

## Reporting a vulnerability

Please do not open a public issue for security problems. Use GitHub private
vulnerability reporting: open the repository's **Security** tab and choose
**Report a vulnerability**.

Include the plugin version, Android version, distributor app, and steps to
reproduce. You can expect an acknowledgement within a few days. Fixes are released as patch
versions and noted in the changelog, with credit if you want it.

## Supported versions

Only the latest released minor version receives security fixes.

## Scope notes

The plugin shows notifications from push messages that were Web Push encrypted to the
device's own keys. Messages that fail decryption are dropped. See the README section
"Security notes" for the trust model.
