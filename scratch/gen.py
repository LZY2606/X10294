# ARINC429 word builder: bit1=LSB=label bit1; label field is bit-reversed vs octal display
def rev8(b):
    r=0
    for i in range(8):
        if b & (1<<i): r |= 1<<(7-i)
    return r
def word(oct_label_int, sdi, data, ssm, force_parity=None):
    lab = rev8(oct_label_int)
    w = lab | (sdi<<8) | ((data & 0x7FFFF)<<10) | ((ssm&3)<<29)
    ones = bin(w).count("1")
    p = 0 if (ones & 1) else 1   # odd parity across 32 bits
    if force_parity is not None: p = force_parity
    w |= p<<31
    return w
def hx(w): return "0x%08X" % w

import datetime
epoch = datetime.datetime(2026,9,19,0,0,0,tzinfo=datetime.timezone.utc)
def ts(us):
    ns = int(epoch.timestamp()*1e9) + us*1000
    iso = (epoch + datetime.timedelta(microseconds=us)).strftime("%Y-%m-%dT%H:%M:%S.%f") + "Z"
    return iso, ns

items=[]
ord_=0
def add(us, ch, w, note):
    global ord_
    ord_+=1
    iso,ns=ts(us)
    items.append((ord_,iso,ch,hx(w),note))

# --- CH-A label 301 BNR altitude, res=1024/2^18=0.00390625 ft/LSB; d=25600 -> 100 ft
# 10 words at 10us (100kHz), then 50ms gap, then 5 words at 80us (12.5kHz)
t=0
for i in range(10):
    d = 25600 + i*256
    w = word(0o301,0,d,3)  # BNR ssm=11 NORMAL
    if i==4: w = word(0o301,0,d,3,force_parity=1-(0 if bin(word(0o301,0,d,3)).count('1')&1 else 1))  # bad parity
    if i==7: w = word(0o301,0,d,0)  # BNR ssm=00 FAILURE WARNING
    add(t,"CH-A",w, f"alt301 hi-rate i{i}")
    t+=10
t += 50000  # acquisition gap 50ms
for i in range(5):
    d = 25600 + 2560 + i*256
    add(t,"CH-A",word(0o301,0,d,3), f"alt301 low-rate i{i}")
    t+=80

# --- CH-A label 205 BNR signed, res = 1/256 kt, positive + negative
add(2000,"CH-A",word(0o205,0,12800,3),"bnr pos 50.0kt")
add(2100,"CH-A",word(0o205,0,(1<<18)-12800,3),"bnr neg -50.0kt")  # two's complement 19-bit

# --- CH-A label 270 BCD, normal digits then illegal nibble A at LSD
bcd_ok = 1 | (2<<4) | (3<<8) | (4<<12) | (5<<16)   # digits LSD-> 1,2,3,4,5 ; MSD bits27-29 zero
add(2200,"CH-A",word(0o270,0,bcd_ok,0),"bcd 54321 normal")  # BCD ssm=00 NORMAL
bcd_bad = 0xA | (2<<4)
add(2300,"CH-A",word(0o270,0,bcd_bad,0),"bcd illegal digit A")

# --- CH-A label 350 discrete, bit 11 set
add(2400,"CH-A",word(0o350,0,1,0),"discrete bit11")
# --- CH-A label 001 reversed presentation demo (raw bits 10000000)
add(2500,"CH-A",word(0o1,0,0,0),"label 001 reversal demo")

# --- CH-B label 301 slow stream 12.5kHz 6 words, ambiguity: observed vs nominal both legal
t=3000
for i in range(6):
    add(t,"CH-B",word(0o301,0,25600+i*256,3),"CH-B 301 steady 12k5")
    t+=80

for it in items: print(it)
print("COUNT",len(items))
# checks
w=word(0o301,0,25600,3)
print("parity check popcount", bin(w).count('1'), hx(w))
print("label raw", bin(w&0xFF), "oct display", oct(rev8(w&0xFF)))
