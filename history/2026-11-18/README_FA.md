# سوابق Magic Phone — ۱۸ نوامبر ۲۰۲۶

این پوشه یک **Historical Snapshot** از مراحل انجام‌شده پروژه Magic Phone است و مبنای توسعه محصول نهایی از صفر محسوب نمی‌شود.

## محتویات

- `HANDOFF_FA.md` — هندآف کامل وضعیت پروژه در ۱۸ نوامبر ۲۰۲۶.
- `source-manifest.json` — فهرست کامل فایل‌های تاریخی و موارد حذف‌شده.
- `source-snapshot-extension-v0.10-bridge-v0.21/` — سورس تاریخی مرتب‌شده بر اساس ساختار واقعی پروژه.
- `bridge/phone_ui.ps1.gz` — نسخه فشرده دقیق فایل PowerShell؛ هش آن در `phone_ui.ps1.sha256` ثبت شده است.

## نکته مهم نسخه‌ها

هندآف اصلی، Baseline آن مرحله را Chrome Extension v0.8 + Local Bridge v0.7 ثبت کرده است. بسته سورس جدیدترِ همین تاریخ، در `README_FA.md` خود نسخه Extension `0.10` و Bridge `0.21` را نشان می‌دهد. هر دو سند بدون بازنویسی تاریخی نگهداری شده‌اند تا سیر واقعی پروژه قابل پیگیری باشد.

## حریم خصوصی

این موارد عمداً وارد GitHub نشده‌اند:

- `bridge/contacts.json`
- `bridge/settings.json`
- `bridge/events.log`
- `bridge/__pycache__/`

بنابراین اطلاعات شخصی مخاطبین، Pairing Secret و Runtime Log در Repository عمومی قرار نگرفته‌اند.

## وضعیت نصب

این بخش فقط **سوابق** است. هیچ نصب‌کننده نهایی یا ادعای Clean-Machine Installation در این Snapshot وجود ندارد.

## مسیر آینده

در مرحله بعد، Magic Phone را به‌صورت یک محصول Windows Native از صفر طراحی و ساخته می‌کنیم و نصب‌کننده نهایی را جداگانه در مسیر انتشار قرار می‌دهیم.
