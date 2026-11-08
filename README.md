# Espelho Android v0.8 — reprodução ao vivo e endereço fixo

Aplicativo gratuito e sem anúncios para Android. Descobre TVs por Wi-Fi Direct, SSDP, mDNS ou IP, com dois caminhos separados: Smart View nativo da Samsung (o Espelho não consegue forçar uma TV encontrada a aparecer na lista do sistema) e transmissão experimental local por navegador.

## Interface v0.4

- Ícone desenhado em vetores com fundo externo **transparente**: `app/src/main/res/drawable/app_icon.xml`.
- SVG editável correspondente: `assets/espelho-icon.svg`.
- Nova interface minimalista inspirada na hierarquia de navegação do iOS: superfícies claras, lilás suave, ícones vetoriais, cartões com cantos arredondados e mensagens de status objetivas.
- Transmissão, favoritos, descoberta por SSDP/mDNS/Wi-Fi Direct, busca por IP, controle de sessão e permissões permanecem funcionais.
- Encontrar uma TV **não significa** que ela esteja conectada; o usuário escolhe Smart View ou transmissão HLS pelo navegador.

## Endereço fixo e acompanhamento ao vivo

- O endereço padrão agora é `http://IP_DO_TABLET:8765/750795`, mantendo o código final **750795** entre sessões. O IP só muda se o roteador atribuir um novo endereço ao tablet. Se quiser manter também o IP, reserve o endereço do tablet no DHCP do roteador.
- O código fixo **não é uma senha segura**: qualquer dispositivo na mesma rede que saiba o endereço pode tentar abrir a transmissão enquanto ela estiver ativa. Inicie a captura apenas em rede doméstica confiável e encerre após o uso.
- O player da TV não apresenta mais controles de vídeo gravado: mostra o estado **AO VIVO**, tenta acompanhar a janela HLS mais recente e oferece **Voltar ao vivo**.
- A playlist HLS usa `EXT-X-MEDIA-SEQUENCE` progressivo, janela deslizante, `EXT-X-TARGETDURATION` fixo e sem `EXT-X-ENDLIST`. Um endpoint local permite ao player detectar uma sessão em andamento e reinicializar se a playlist ficar estagnada.
- O navegador e o hardware da TV podem impor latência ou falhar no seek. HLS **não é tempo real de latência zero**, e a compatibilidade ainda deve ser validada no dispositivo.

## Consentimento e diagnóstico da captura v0.6

- Cada sessão usa `MediaProjectionManager.createScreenCaptureIntent()` para solicitar autorização de captura ao Android. No Android 14+, o aplicativo pede a **tela inteira**, evitando compartilhar somente a janela do Espelho.
- Se um endereço já estiver ativo, **Reiniciar captura** encerra a sessão antiga antes de solicitar autorização novamente. A autorização não é reutilizada.
- O estado da interface agora diferencia a permissão concedida, a produção de quadros H.264, o número de segmentos HLS e as interrupções no codificador. Abrir o servidor HTTP não implica mais que o vídeo esteja funcionando.
- O aplicativo mostra uma notificação de serviço enquanto transmite. Os indicadores da interface do sistema variam conforme a versão do Android.
- Se a televisão mostrar tela preta, observe o campo **Quadros de vídeo**: valor sempre zero indica problema antes do envio; valor crescente indica captura funcionando e direciona o diagnóstico ao HLS/player da Samsung.

## Player local v0.7: compatibilidade ampliada

- Corrigido o principal problema da versão anterior: `<video src="live.m3u8">` não funciona em muitos navegadores, mesmo quando o servidor HLS está ativo. Agora o player inclui **HLS.js**, capaz de reproduzir HLS pelo MediaSource em Chrome/Firefox/Edge Android ou desktop.
- O JavaScript HLS.js v1.7.4 e sua licença Apache-2.0 são incluídos **dentro do APK pelo workflow GitHub Actions**. A televisão e o tablet não precisam de internet para reproduzir; tudo é servido pela rede local.
- **Fallback nativo** para Smart TVs Samsung/Tizen, Safari e browsers com suporte HLS; botão `Player alternativo` permite alternar quando disponível.
- Tela simples com botões `Reproduzir ao vivo`, `Reconectar` e `Player alternativo`, sem exigir que o usuário configure codecs. Mensagens claras quando falta suporte ou há erro de decodificação.
- Endereço continua `http://IP_DO_TABLET:8765/750795`. O stream de áudio interno permanece AAC; quadros de vídeo continuam H.264.
- Foi incluído teste que gera amostra H.264 por FFmpeg e verifica, usando FFprobe e FFmpeg, se os segmentos MPEG-TS podem ser reconhecidos e decodificados.
- Não é tecnicamente possível garantir todos os navegadores: os que não suportam HLS nativo nem MediaSource não poderão reproduzir este formato; o app informa essa limitação sem mostrar um vídeo falso.

## Experiência de vídeo v0.8: tela cheia e rotação real

- No navegador da TV, **Tela cheia** usa Fullscreen API e APIs prefixadas quando disponíveis; quando o navegador nega, a página alterna para um modo cinema que ocupa a área visível da página. O botão **Sair da tela cheia**, a tecla Escape ou a tecla Back do controle da Samsung fecham o modo cinema.
- O botão **Preencher TV** escolhe `object-fit: cover` para preencher a tela (pode cortar as bordas); **Mostrar imagem inteira** preserva a imagem sem cortes (`contain`).
- Quando o tablet gira, o app mantém o **mesmo MediaProjection e o mesmo VirtualDisplay** autorizados. A tela virtual é redimensionada e o codificador H.264 recebe uma nova superfície na orientação correta. **A rotação não pede nova autorização**.
- No Android 14+, usamos o callback `onCapturedContentResize`. No Android 13 e anteriores, a orientação é verificada periodicamente com as dimensões reais do display e um listener de alterações.
- O HLS adiciona `#EXT-X-DISCONTINUITY` na mudança de formato para que o player reprocesse a resolução e a proporção, sem confundir quadros da orientação antiga com a nova.
- Reduzimos o `EXT-X-TARGETDURATION` anunciado de 6 para 4 segundos, preservando segmentos de aproximadamente 2 segundos e mantendo o buffer voltado à reprodução contínua, não à latência mínima.
- **Limite importante**: um navegador que não oferece Fullscreen API pode impedir que a página esconda a barra própria da TV; o modo cinema usa o máximo de área disponível, mas não remove a interface do navegador que o firmware controla.
- A mudança de orientação e o modo cinema dependem de teste no Samsung Tab S6 Lite e no navegador da CU7700; os testes automatizados não substituem essa validação.

## Transmissão com som (HLS)

Esta versão substitui a transmissão JPEG a ~4 quadros por segundo por vídeo H.264 codificado por hardware (MediaCodec), com taxa de quadros ajustada à tela e à capacidade do encoder, e áudio AAC interno capturado com AudioPlaybackCapture (Android 10 ou superior).

1. Instale o APK v0.8 e abra o Espelho no tablet.
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
