# Controle Remoto Dedicado do Projetor (Android APK)

Aplicativo Android nativo criado especificamente para controlar o projetor no IP **`192.168.2.41:5555`** diretamente pelo celular via Wi-Fi (ADB sobre TCP/IP), sem necessidade de computador intermediário.

---

## 📱 Recursos Inclusos no App

- **Conexão Automática:** Já pré-configurado para conectar diretamente em `192.168.2.41:5555`.
- **D-Pad de Alta Precisão:**
  - Cima, Baixo, Esquerda, Direita
  - Botão Central **OK**
- **Navegação de Sistema:**
  - Voltar, Início (Home), Menu
- **Controle de Volume e Áudio:**
  - Volume +, Volume -, Mute instantâneo
- **Liga / Desliga:** Botão Power direto via `KEYCODE_POWER`
- **Atalhos Rápidos Personalizados:**
  - **Projectivy Launcher:** Abre o launcher padrão instantaneamente
  - **YouTube:** Abre o aplicativo do YouTube do projetor
  - **Ajustes:** Abre a tela de configurações do Android TV
- **Digitação de Texto Remota:** Diálogo para digitar pelo teclado do celular e enviar palavras/buscas inteiras diretamente para o projetor.
- **Feedback Tátil:** Vibração suave a cada clique (haptic feedback).
- **Indicador de Conexão em Tempo Real:**
  - 🟢 Verde: Conectado e pronto para comandos
  - 🟡 Amarelo: Conectando / aguardando autorização
  - 🔴 Vermelho: Desconectado / fora da rede

---

## 🛠️ Como Gerar o Arquivo `.apk`

Você tem duas formas simples de gerar o instalador `.apk`:

### Método 1: Pelo Android Studio (Local)
1. Baixe ou abra o [Android Studio](https://developer.android.com/studio).
2. Clique em **File > Open** e selecione a pasta `ProjectorRemote`.
3. Aguarde o Gradle sincronizar as dependências.
4. Conecte seu celular Android via cabo USB (com depuração ativada) e clique em **Run (botão verde de Play)** para instalar direto no celular.
   * *Ou:* Vá no menu superior **Build > Build Bundle(s) / APK(s) > Build APK(s)** para gerar o arquivo `.apk` e enviar para o celular por WhatsApp, Telegram ou Google Drive.

### Método 2: Pelo GitHub Actions (100% na Nuvem, Sem Instalar Nada no PC)
O projeto já conta com o workflow `.github/workflows/build-apk.yml`.
1. Suba esta pasta para um repositório seu no GitHub (público ou privado).
2. Na aba **Actions** do seu GitHub, o fluxo iniciará automaticamente.
3. Em cerca de 1 a 2 minutos, o arquivo `ProjectorRemote-APK.zip` estará pronto para download direto na página da Action!

---

## 📲 Como Usar no Celular
1. Certifique-se de que o celular está conectado na **mesma rede Wi-Fi** do projetor.
2. Abra o app no celular.
3. Na primeira vez que conectar, o projetor exibirá um aviso na tela:
   > *"Permitir depuração USB deste dispositivo?"*
   - Marque a caixa **"Sempre permitir"** e confirme com o controle remoto original.
4. Pronto! O status ficará verde e você poderá controlar tudo pelo celular.
