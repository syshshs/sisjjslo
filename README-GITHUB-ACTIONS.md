# ساخت APK با GitHub Actions

1. کل محتویات این پروژه را داخل یک Repository در GitHub آپلود کنید.
2. بهتر است نام شاخه اصلی `main` باشد.
3. بعد از Push کردن، از تب **Actions** وارد workflow با نام **Build APK** شوید.
4. پس از پایان موفق Build، در صفحه اجرای workflow بخش **Artifacts** فایل `ReNo-VPN-debug-apk` را دریافت کنید.
5. برای اجرای دستی نیز از **Actions → Build APK → Run workflow** استفاده کنید.

این workflow نسخه Debug را می‌سازد و برای تست و نصب روی گوشی مناسب است. برای انتشار عمومی، بهتر است بعداً Release APK را با keystore امن امضا کنیم.
