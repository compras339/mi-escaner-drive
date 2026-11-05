name: Build APK

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

permissions:
  contents: write

jobs:
  assemble:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Set up Java 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Restore fixed debug keystore
        run: |
          echo "${{ secrets.DEBUG_KEYSTORE_B64 }}" | base64 -d > app/debug.keystore
          keytool -list -v -keystore app/debug.keystore -storepass android | grep SHA1

      - name: Read version
        run: |
          VER=$(tr -d ' \r\n' < VERSION)
          echo "VER=$VER" >> $GITHUB_ENV
          echo "Versión $VER, build ${{ github.run_number }}"

      - name: Make Gradle wrapper executable
        run: chmod +x ./gradlew

      - name: Build Debug APK
        run: ./gradlew assembleDebug --no-daemon -PappVersionCode=${{ github.run_number }} -PappVersionName="${{ env.VER }}"

      - name: Show APK signing SHA-1
        run: |
          APKSIGNER=$(ls -d $ANDROID_HOME/build-tools/*/apksigner | sort -V | tail -1)
          "$APKSIGNER" verify --print-certs app/build/outputs/apk/debug/app-debug.apk | grep -i "SHA-1"

      - name: Rename APK
        run: cp app/build/outputs/apk/debug/app-debug.apk EscanerCompras.apk

      - name: Upload APK artifact
        uses: actions/upload-artifact@v4
        with:
          name: EscanerCompras-v${{ env.VER }}-build${{ github.run_number }}
          path: EscanerCompras.apk

      # Publica la Release con la CLI oficial de GitHub. Si ya existe una Release con esta
      # etiqueta (p. ej. por un re-run), se borra y se vuelve a crear con el APK.
      - name: Publish Release
        if: github.ref == 'refs/heads/main'
        env:
          GH_TOKEN: ${{ github.token }}
          TAG: v${{ env.VER }}-build${{ github.run_number }}
        run: |
          gh release delete "$TAG" --cleanup-tag --yes 2>/dev/null || true
          for i in 1 2 3; do
            if gh release create "$TAG" EscanerCompras.apk \
                 --title "Escáner de Compras v${{ env.VER }} (build ${{ github.run_number }})" \
                 --notes "Versión ${{ env.VER }}, build ${{ github.run_number }}. Descarga directa: https://github.com/${{ github.repository }}/releases/latest/download/EscanerCompras.apk"; then
              echo "Release publicada: $TAG"; exit 0
            fi
            echo "Intento $i falló; reintentando en 15 s…"; sleep 15
            gh release delete "$TAG" --cleanup-tag --yes 2>/dev/null || true
          done
          echo "No se pudo publicar la Release"; exit 1
