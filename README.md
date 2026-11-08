# Espelho Android v0.4 — interface minimalista e ícone vetorial

Aplicativo gratuito e sem anúncios para Android. Descobre TVs por Wi-Fi Direct, SSDP, mDNS ou IP, com dois caminhos separados: Smart View nativo da Samsung (o Espelho não consegue forçar uma TV encontrada a aparecer na lista do sistema) e transmissão experimental local por navegador.

## Interface v0.4

- Ícone desenhado em vetores com fundo externo **transparente**: `app/src/main/res/drawable/app_icon.xml`.
- SVG editável correspondente: `assets/espelho-icon.svg`.
- Nova interface minimalista inspirada na hierarquia de navegação do iOS: superfícies claras, lilás suave, ícones vetoriais, cartões com cantos arredondados e mensagens de status objetivas.
- Transmissão, favoritos, descoberta por SSDP/mDNS/Wi-Fi Direct, busca por IP, controle de sessão e permissões permanecem funcionais.
- Encontrar uma TV **não significa** que ela esteja conectada; o usuário escolhe Smart View ou transmissão HLS pelo navegador.

## Transmissão com som (HLS)

Esta versão substitui a transmissão JPEG a ~4 quadros por segundo por vídeo H.264 codificado por hardware (MediaCodec), com taxa de quadros ajustada à tela e à capacidade do encoder, e áudio AAC interno capturado com AudioPlaybackCapture (Android 10 ou superior).

1. Instale o APK v0.4 e abra o Espelho no tablet.
2. Toque em **Iniciar transmissão da tela** e aceite as permissões de áudio e captura de tela.
3. No navegador da sua TV Samsung, abra **o endereço completo** mostrado pelo Espelho.
4. Pressione **Reproduzir vídeo e áudio** na TV. Se necessário, abra o link **player da TV** oferecido na página.
5. Para finalizar, toque **Parar transmissão** no tablet.

O endereço e o token de seis dígitos são locais e mudam a cada sessão. Não exige conta, serviço externo ou publicidade.

## Limitações importantes

- **Não existe FPS ilimitado:** o aplicativo deixa de descartar quadros artificialmente, mas o máximo depende da tela, do hardware H.264 e da rede. O serviço negocia uma taxa-alvo que o encoder declare suportar. Não foram realizadas medições reais na Samsung/Tab S6 Lite.
- **Áudio interno:** Android 10+ e permissão RECORD_AUDIO. Somente apps que autorizam captura; áudio DRM/protegido ou de apps que a bloqueiem pode não ser capturado. O microfone não é transmitido.
- **HLS aumenta a latência** (normalmente vários segundos); este protótipo é voltado a vídeo/reprodução, não a jogos. Não há garantia de reprodução no navegador de todos os modelos/anos de Samsung TV.
- Transmissão usa HTTP na rede local, com link de acesso temporário. Quem tiver o link completo poderá assistir enquanto ativa a sessão. Use rede confiável.
- O tamanho de vídeo é reduzido quando necessário para permitir codificação fluida. Mudanças de orientação podem exigir reiniciar a transmissão.
- Conteúdo com FLAG_SECURE pode exibir tela preta.
- O projeto é experimental; compilar não equivale a validar áudio/vídeo no aparelho.

## Compilação

O GitHub Actions executa `gradle --no-daemon :app:assembleDebug` e publica `espelho-android-debug`. Baixe o APK no histórico do workflow. Projeto Android com Kotlin, Gradle 8.11.1, JDK 17.

## Fontes técnicas
- Android MediaProjection / AudioPlaybackCapture: https://developer.android.com/media/platform/av-capture
- Samsung Tizen Streaming: https://developer.samsung.com/smarttv/develop/specifications/general-specifications.html
