# Espelho Android v0.2 — descoberta e espelhamento local experimental

Aplicativo sem anúncios para encontrar TVs (SSDP, mDNS, Wi-Fi Direct e IP) e testar dois caminhos de espelhamento:

1. **Smart View (Samsung):** abre o seletor nativo da Samsung, quando a versão do tablet permite. A detecção de dispositivos do Espelho **não** se transfere para a lista do Smart View; isso depende do sistema.
2. **Navegador da TV:** transmite capturas da tela como imagens JPEG sobre HTTP na mesma rede Wi-Fi. A TV precisa ter navegador próprio, abrir o endereço exibido no tablet e permanecer conectada à rede local. A permissão de captura do Android deve ser aceita antes de começar.

## Como testar a transmissão pelo navegador

1. No tablet, abra Espelho > **Iniciar transmissão da tela** e aceite a solicitação do Android.
2. O aplicativo exibirá um endereço local como `http://192.168.1.20:8765/123456`. O endereço real só aparecerá depois que o serviço começar.
3. Abra o navegador de internet da Smart TV Samsung e digite o endereço completo, inclusive os seis dígitos finais.
4. Para terminar, volte ao Espelho e toque em **Parar transmissão** (ou interrompa a captura no Android).

## Limitações

- Este método experimental **não é Miracast nem Google Cast** e não transmite áudio. A taxa de quadros e a latência são limitadas (aprox. 4 imagens/segundo sob condições favoráveis), por isso não é indicado para jogos ou filmes.
- O navegador de algumas TVs pode não executar o código da página ou pode restringir conexões HTTP locais. A reprodução na sua TV ainda depende de teste físico.
- A transmissão usa somente a rede local, sem servidor externo. Quem tiver acesso ao endereço completo durante uma sessão poderá ver a tela; use somente redes domésticas confiáveis. Um PIN temporário integra o link.
- Imagens de conteúdo protegido podem aparecer em preto pelas proteções do Android.
- A detecção da TV não comprova suporte Miracast e não autoriza conexão direta. Android restringe o acesso de aplicativos comuns aos controles internos Wi-Fi Display.
- Se a orientação do tablet mudar, reinicie a transmissão para ajustar o enquadramento.
- O projeto compila um APK debug via GitHub Actions, mas o funcionamento no Tab S6 Lite e na TV ainda precisa ser testado.

## Compilar

Em **Actions → Build APK**, execute o workflow ou faça um push. Baixe o artefato `espelho-android-debug` com `app-debug.apk`. Android Studio com JDK 17/Gradle 8.11.1 também funciona.

## Permissões

Rede local (INTERNET/Wi-Fi) para descoberta e transmissão, Wi-Fi Direct para detecção P2P, e serviço de mídia em primeiro plano para captura autorizada pelo Android. O app não usa nuvem, anúncios ou cadastro.
