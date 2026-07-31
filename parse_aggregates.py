#!/data/data/com.termux/files/usr/bin/python3
import sys, json
data = json.load(sys.stdin)
coins = data.get('data', {})
# Top by quoteVolume
top = sorted(coins.items(), key=lambda x: x[1].get('quoteVolume', 0) if isinstance(x[1], dict) else 0, reverse=True)[:15]
print('=== ТОП 15 ПО ОБЪЁМУ (quoteVolume) ===')
for i, (sym, d) in enumerate(top, 1):
    if isinstance(d, dict):
        p = d.get('price', 0)
        ch = d.get('change24h', 0)
        vol = d.get('quoteVolume', 0)
        vola = d.get('volatility24h', 0)
        arrow = '🟢' if ch > 0 else '🔴'
        print(f'{i}. {sym:12s} ${p:<10.4f}  {arrow} {ch:>+7.2f}%  Vol: ${vol:>12,.0f}  Вола: {vola:.1f}%')
