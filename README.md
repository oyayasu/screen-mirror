# Espelho Android v0.1 — protótipo de descoberta

Aplicativo experimental para localizar TVs na rede e ajudar a iniciar o espelhamento nativo do Android. **Não implementa um transmissor Miracast nem garante espelhamento.**

## O que faz
- Procura dispositivos por SSDP/UPnP, descoberta de serviço mDNS/NSD e Wi-Fi Direct (P2P).
- Procura potenciais TVs Samsung na sub-rede IPv4 local por portas de controle TCP (8001, 8002 e 55000). Essa detecção é heurística; a porta pode pertencer a outro equipamento e não comprova compatibilidade com espelhamento.
- Permite testar um IP manualmente, salvar dispositivos favoritos e abrir a tela de transmissão do Android.
- Interface em português, sem anúncios, contas, rastreadores ou backend.

## Compilação via GitHub Actions
1. Crie um repositório GitHub e envie o conteúdo deste ZIP mantendo as pastas.
2. Em **Actions**, escolha **Build APK** > **Run workflow** (ou faça um push).
3. Na execução concluída, baixe o artefato `espelho-android-debug`, que contém `app-debug.apk`.

Ou abra o projeto no Android Studio com JDK 17 e Gradle 8.11.1; como o ZIP não inclui o binário do Gradle Wrapper, o Android Studio deverá ter Gradle configurado ou disponibilizado por ele.

## Permissões
- Android 13+: dispositivos Wi-Fi próximos para descoberta P2P.
- Android 12 ou anterior: localização para descoberta P2P; o Android também exige que o serviço de localização esteja habilitado.
- Acesso à rede local para descoberta e teste de endereço IP.

## Limitações
- Aparecer no resultado **não** significa que a TV aceite espelhamento.
- "Abrir espelhamento" abre o painel do sistema Android, que ainda pode não mostrar a TV.
- O botão "Procurar IPs" faz uma busca restrita à faixa local IPv4 /24; não acessa a internet e não executa pareamento.
- O resultado depende de firmware/ano/modelo da TV e das restrições de rede e do Android.
- Versão de código-fonte experimental: APK e comportamento no tablet ainda não foram validados.
