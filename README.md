# probable-couscous · tokenarb

Scanner che confronta il prezzo degli **stock tokenizzati** (xStocks su Kraken e Bybit) con il prezzo delle stesse azioni **quotate a Wall Street**, ogni **15 minuti**, calcola la **size ottimale e il profitto netto** dell'arbitraggio e invia **notifiche** (Telegram, Discord, ntfy).

## Come funziona

1. **Prezzo Borsa**: ultimo prezzo a 1 minuto da Yahoo Finance (incluso pre/after-market) o da Finnhub.
2. **Prezzo token**: order book completo del venue (Kraken `AAPLxUSD`, Bybit `AAPLXUSDT` convertito da USDT a USD).
3. **Gap** = (mid del token − prezzo Borsa) / prezzo Borsa, in basis point.
4. **Direzione**: token sotto il prezzo di Borsa → *compra token / vendi azione*; sopra → *vendi token / compra azione*.
5. **Size e profitto**: lo scanner "cammina" l'order book livello per livello. Ogni livello peggiora il prezzo medio, quindi il profitto netto cresce, raggiunge un massimo e poi cala: lo scanner trova il punto massimo entro `max_notional_usd`.

   ```
   netto = quantità × (prezzo vendita − prezzo acquisto)
           − fee taker venue − commissioni broker − metà spread azione − costi fissi
   ```

   Riporta anche il risultato con una size fissa (`trade_size_usd`) per confronto.
6. **Filtri**: notifica solo se la Borsa è aperta (sessioni in `tradable_sessions`), il prezzo è fresco e profitto netto ≥ `min_net_profit_usd` e ≥ `min_net_bps`. Gli alert ripetuti sono silenziati per `cooldown_minutes`, salvo allargamento del gap.

## Avvio rapido (locale o VPS)

```bash
pip install -r requirements.txt
python -m tokenarb scan --no-notify   # una scansione, tabella a console
python -m tokenarb run                 # loop ogni 15 minuti (:00 :15 :30 :45)
```

Ogni scansione viene salvata in `data/scans.csv`.

## Notifiche

Imposta una o più variabili d'ambiente (o secret su GitHub):

| Canale   | Variabili |
|----------|-----------|
| Telegram | `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` |
| Discord  | `DISCORD_WEBHOOK_URL` |
| ntfy     | `NTFY_TOPIC` (opz. `NTFY_SERVER`) |

Prova: `python -m tokenarb test-notify`

## GitHub Actions (ogni 15 minuti)

Il workflow `.github/workflows/scanner.yml` esegue una scansione ogni 15 minuti. Aggiungi i secret in *Settings → Secrets and variables → Actions*. Lo storico CSV è scaricabile come artifact di ogni esecuzione.

**Limiti noti**
- I runner GitHub sono negli USA e **Bybit blocca gli IP USA**: sul workflow Bybit è disattivato di default (variabile `TOKENARB_DISABLE_VENUES`). Per includerlo, esegui `python -m tokenarb run` su un server europeo.
- Il cron di GitHub può ritardare di qualche minuto nei momenti di traffico.

## Configurazione

Tutto è in `config.yaml`: ticker, simboli per venue, fee, costi broker, soglie, capitale massimo, sessioni operabili, moltiplicatore azioni/token.

## Avvertenze

- Fuori dagli orari di Borsa il token continua a quotare ma l'azione no: il gap è **indicativo** e non coperto. Lo scanner lo registra ma non lo notifica.
- La strategia *compra token / vendi azione* richiede di poter andare short sull'azione (o di possederla). *Vendi token* richiede inventario di token.
- Verifica le fee effettive del tuo account: i valori in `config.yaml` sono indicativi.
- Non è consulenza finanziaria.
