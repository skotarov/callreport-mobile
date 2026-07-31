# Relationship Manager — Google Play Internal testing

Пакет: `com.onlineimoti.relationshipmanager`  
Продукт за фирмен лиценз: `rm_company_license`

## Какво прави автоматизацията

Workflow-ът **Play Internal Release**:

1. възстановява upload keystore само в временната GitHub машина;
2. създава подписани `release` APK и AAB;
3. проверява подписите им;
4. качва APK, AAB и ProGuard mapping като GitHub artifact;
5. при избрано `publish_internal=true` качва AAB в Google Play Internal testing;
6. изтрива временните ключове и JSON файлове дори при неуспешен build.

## GitHub Actions secrets

В Repository settings → Secrets and variables → Actions трябва да бъдат добавени:

- `RM_UPLOAD_KEYSTORE_B64` — целият upload keystore, кодиран с `base64 -w 0`;
- `RM_UPLOAD_STORE_PASSWORD` — паролата на keystore;
- `RM_UPLOAD_KEY_ALIAS` — `relationship-manager-upload`;
- `RM_UPLOAD_KEY_PASSWORD` — паролата на ключа;
- `RM_PLAY_SERVICE_ACCOUNT_JSON` — пълното съдържание на Google service-account JSON файла.

Keystore файлът и паролите не се commit-ват. `.gitignore` вече изключва `*.jks`, `*.keystore`, `play-signing.properties`, `*.apk` и `*.aab`.

## Първоначална настройка в Google Play Console

1. Създава се приложението **Relationship Manager** с package name `com.onlineimoti.relationshipmanager`.
2. Включва се Play App Signing. Създаденият от нас ключ се използва като **upload key**.
3. Създава се Internal testing списък с Google акаунтите на тестерите.
4. В Google Cloud се създава service account с включен Android Publisher API. Той се добавя в Play Console → Users and permissions с права за releases и in-app products.
5. В Monetize → Products → In-app products се създава еднократният продукт `rm_company_license`.
6. Попълват се App content, Data safety, privacy-policy URL и декларациите за чувствителните разрешения.

Приложението заявява SMS, Call Log, Contacts и All files access разрешения. Google Play може да изиска отделни декларации и доказване, че тези разрешения са част от основната функция. Internal testing също може да бъде блокиран, докато задължителните декларации не бъдат попълнени.

## Пускане на версия

GitHub → Actions → **Play Internal Release** → Run workflow.

- `version_code`: може да остане празно — генерира се на база UTC дата и час;
- `version_name`: например `0.4.0-internal`;
- `publish_internal=false`: само създава подписаните файлове;
- `publish_internal=true`: създава файловете и публикува AAB в Internal testing.

След успешното първо публикуване Play Console показва tester opt-in линк. Приложението трябва да се инсталира от този линк през Google Play, а не чрез ръчно отваряне на APK, за да се избегне предупреждението за непознат sideloaded инсталатор и за да работи Google Play Billing.
