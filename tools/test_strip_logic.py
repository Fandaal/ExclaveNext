import re

SPEED_EMOJI = '(?:\u2728|\u2B50\uFE0F?|\U0001F3C1|\U0001F3F3\uFE0F|\U0001F3F4(?:\u200D\u2620\uFE0F)?)'
SPEED_RE = re.compile('^' + SPEED_EMOJI + r'(?:\s+[0-9]+(?:\.[0-9]+)?)*\s+')

def strip_speed(n):
    return SPEED_RE.sub('', n)

def speed_marker(n):
    m = SPEED_RE.match(n)
    if not m:
        return ''
    v = m.group(0)
    head = ''
    for c in v:
        if c.isdigit() or c == '.':
            break
        head += c
    num = re.search(r'[0-9]+(?:\.[0-9]+)?', v)
    if num:
        return head.rstrip() + ' ' + num.group(0) + ' '
    return head

def first_flag_idx(n):
    i = 0
    while i < len(n):
        cp = ord(n[i])
        if 0x1F1E6 <= cp <= 0x1F1FF:
            return i
        i += 1
    return -1

def strip_geo(n):
    i = first_flag_idx(n)
    return n[:i].rstrip() if i >= 0 else n

def geo_tag(n):
    i = first_flag_idx(n)
    return n[i:] if i >= 0 else ''

cases = [
    ('\U0001F3C1 24.7 23.9 24.0 0.0 Germany (OVH)', 'stacked speeds legacy'),
    ('\u2728 42.3 tg:VLESSFORU', 'single speed'),
    ('\U0001F3F3\uFE0F 5.1 name', 'white flag + speed'),
    ('\u26A1 t.me/rjsxrd', 'lightning NOT a speed marker'),
    ('\U0001F3F4 Dead proxy', 'black flag no number'),
    ('\u2B50\uFE0F 30.0 name', 'star with FE0F'),
]
for name, desc in cases:
    print(f'{desc}:')
    print('  in     :', repr(name))
    print('  stripped:', repr(strip_speed(name)))
    print('  marker :', repr(speed_marker(name)))

gcases = [
    'Бесплатный VPN tg:VLESSFORU \U0001F1F8\U0001F1EA Sweden (Alexhost)',
    'tg:VLESSFORU \U0001F1F5\U0001F1F1 \u26A1 t.me/rjsxrd \U0001F1F5\U0001F1F1 Poland (Paul)',
    '\U0001F1F7\U0001F1FA Beget — #4 \U0001F1F7\U0001F1FA Russian Federation (Beget)',
]
for n in gcases:
    print('geo:')
    print('  in     :', repr(n))
    print('  stripped:', repr(strip_geo(n)))
    print('  tag    :', repr(geo_tag(n)))

# transferAnnotations simulation
old = '\u2728 42.3 tg:VLESSFORU \U0001F1F8\U0001F1EA Sweden (Alexhost)'
fresh = 'tg:VLESSFORU NEW'
sp = speed_marker(old)
gt = geo_tag(old)
base = strip_speed(strip_geo(fresh)).strip()
if gt:
    base = (base + ' ' + gt.strip()).strip()
if sp:
    base = (sp.strip() + ' ' + base).strip()
print('transfer:', repr(base))

# speedTest rewrite simulation: old name -> after 2nd run at 10 Mbps (🏁)
name = '\U0001F3C1 24.7 23.9 24.0 0.0 tg:VLESSFORU \U0001F1F5\U0001F1F1 Poland (Paul)'
marker = '\U0001F3C1'
new_speed = '10.5'
base = strip_speed(name)
rewritten = f'{marker} {new_speed} {base}'.strip()
print('speedTest re-run:', repr(name), '->', repr(rewritten))
