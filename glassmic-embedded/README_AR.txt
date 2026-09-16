GlassMic Embedded Probe v0.1 — سورس غير مبني وغير مجرّب

هيدي مش نسخة واتساب معدّلة جاهزة للتثبيت.
تعذّر البناء بهالجلسة: Android SDK/NDK وapktool وأدوات التوقيع مش موجودين، وتنزيل Android SDK فشل بالاتصال.

التحضير الموجود:
- سورس GlassMic native مثبت على commit مذكور بـUPSTREAM.txt، مع الرخصة الأصلية.
- مدخل Java مستقل عن LSPosed وعن تطبيق GlassMic وContentProviders.
- ShadowHook مع arm64 و16KB alignment حسب CMake الأصلي.
- شاشة اختبار تنضاف إلى واتساب بعد الدمج: ARM ثم بدء مكالمة تجريبية خلال 30 ثانية.
- نغمة 440Hz منخفضة المستوى مولّدة مباشرة كـPCM16 mono 48kHz.
- يبدأ العد بعد أول hook hit، ثم يتوقف الاختبار بعد نحو 3 ثواني ويعود إلى REAL_MIC.
- زر STOP؛ وضع الميكروفون الحقيقي هو الافتراضي. لا توجد أي محاولة لإخفاء الفحص أو تجاوز تحقق التوقيع.
- لا تغيير لقاعدة بيانات واتساب أو تسجيل الدخول أو سيرفراته.

البناء المطلوب على بيئة Android متوفّرة:
JDK 17، Gradle 8.9، Android SDK 35، NDK 26.1.10909125، CMake 3.22.1.
نفّذ gradle :app:assembleDebug.
الناتج app/build/outputs/apk/debug/app-debug.apk هو payload، ما بينحقن بواتساب إذا تثبّت لحاله.

التركيب اللاحق:
1. دمج APKS كاملاً بأداة APKEditor/Antisplit متوافقة.
2. فك APK واتساب المدموج وAPK الـpayload بواسطة apktool.
3. python merge_decoded.py decoded_whatsapp decoded_probe staged_whatsapp
4. إعادة البناء بواسطة apktool، zipalign المناسب للمكتبات native، ثم apksigner verify بعد التوقيع.
لا تثبّت نتيجة غير متحقق منها. توقيع مختلف لا يحدّث نسخة واتساب الأصلية مباشرة؛ ما تحذف نسختك الحالية لتجربة هالحزمة.

ما لم يُثبت:
- نجاح ترجمة native أو APK؛ فحص صياغة Java فقط متاح هنا.
- مرور مسار مكالمة واتساب Android 16 بهذه الـhooks.
- نجاح تشغيل APK واتساب بعد إعادة التوقيع.
- قد تلتقط hooks تسجيلات أخرى ضمن عملية التطبيق أثناء فترة ARM، لذا اختبر مكالمة تجريبية فقط.
- مسار AudioRecord الأصلي من GlassMic يخمّن تنسيق الصوت من ذاكرة الكائن؛ يحتاج تحقق فعلي على الجهاز.
- لا TTS ولا B4A ولا queue في هذه المرحلة؛ نغمة واحدة لاختبار المبدأ.
