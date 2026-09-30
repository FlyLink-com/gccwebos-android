#!/usr/bin/env python3
"""Offline packet-path test: AF_UNIX fake TUN -> gVisor -> SS AEAD -> loopback fixture.
No external address is dialed. A TUN packet's synthetic destination is decoded and
answered by the loopback Shadowsocks fixture, never forwarded to the network.
"""
import ctypes, hashlib, ipaddress, json, os, socket, struct, subprocess, sys, tempfile, threading, time
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes
ROOT=os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PASSWORD=b'lab-only-password'
KEY=hashlib.md5(PASSWORD).digest()
def subkey(salt):return HKDF(algorithm=hashes.SHA1(),length=16,salt=salt,info=b'ss-subkey').derive(KEY)
def readn(s,n):
 b=b''
 while len(b)<n:
  c=s.recv(n-len(b))
  if not c:raise EOFError()
  b+=c
 return b
class AEADStream:
 def __init__(self,s):self.s=s;self.enc=None;self.dec=None;self.rn=0;self.wn=0
 def read(self):
  if self.dec is None:self.dec=AESGCM(subkey(readn(self.s,16)))
  n=struct.unpack('!H',self.dec.decrypt(self.rn.to_bytes(12,'little'),readn(self.s,18),None))[0];self.rn+=1
  out=self.dec.decrypt(self.rn.to_bytes(12,'little'),readn(self.s,n+16),None);self.rn+=1;return out
 def write(self,data):
  prefix=b''
  if self.enc is None:
   salt=os.urandom(16);prefix=salt;self.enc=AESGCM(subkey(salt))
  prefix+=self.enc.encrypt(self.wn.to_bytes(12,'little'),struct.pack('!H',len(data)),None);self.wn+=1
  prefix+=self.enc.encrypt(self.wn.to_bytes(12,'little'),data,None);self.wn+=1;self.s.sendall(prefix)
def checksum(b):
 if len(b)%2:b+=b'\0'
 s=sum(struct.unpack('!%dH'%(len(b)//2),b));s=(s&65535)+(s>>16);s=(s&65535)+(s>>16);return (~s)&65535
def packet(src,dst,sport,dport,seq=0,ack=0,flags=0x10,payload=b'',udp=False):
 a=socket.inet_aton(src);b=socket.inet_aton(dst);proto=17 if udp else 6
 if udp:t=struct.pack('!HHHH',sport,dport,len(payload)+8,0)+payload
 else:t=struct.pack('!HHIIBBHHH',sport,dport,seq,ack,5<<4,flags,65535,0,0)+payload
 c=checksum(a+b+struct.pack('!BBH',0,proto,len(t))+t)
 if udp:t=t[:6]+struct.pack('!H',c)+t[8:]
 else:t=t[:16]+struct.pack('!H',c)+t[18:]
 h=struct.pack('!BBHHHBBH4s4s',0x45,0,20+len(t),1,0,64,proto,0,a,b);h=h[:10]+struct.pack('!H',checksum(h))+h[12:];return h+t
def ipv6tcp():
 src=socket.inet_pton(socket.AF_INET6,'fdfe:dcba:9876::1');dst=socket.inet_pton(socket.AF_INET6,'2001:db8::10')
 tcp=struct.pack('!HHIIBBHHH',34570,18080,2000,0,5<<4,2,65535,0,0)
 c=checksum(src+dst+struct.pack('!I3xB',len(tcp),6)+tcp);tcp=tcp[:16]+struct.pack('!H',c)+tcp[18:]
 return struct.pack('!IHBB16s16s',6<<28,len(tcp),6,64,src,dst)+tcp
def icmp():
 body=struct.pack('!BBHHH',8,0,0,1234,1)+b'GCC_ICMP_MUST_STAY_LOCAL'
 body=body[:2]+struct.pack('!H',checksum(body))+body[4:]
 a=socket.inet_aton('172.19.0.1');b=socket.inet_aton('198.51.100.10')
 h=struct.pack('!BBHHHBBH4s4s',0x45,0,20+len(body),2,0,64,1,0,a,b)
 return h[:10]+struct.pack('!H',checksum(h))+h[12:]+body
def parse(b):
 ihl=(b[0]&15)*4;proto=b[9];h=b[ihl:]
 if proto==6:
  sp,dp,seq,ack,off,flags=struct.unpack('!HHIIBB',h[:14]);return proto,sp,dp,seq,ack,flags,h[(off>>4)*4:]
 if proto==17:
  sp,dp=struct.unpack('!HH',h[:4]);return proto,sp,dp,0,0,0,h[8:]
 return proto,0,0,0,0,0,b''

def scenario(mode):
 lib=ctypes.CDLL(os.path.join(ROOT,'build/libgccmihomo-host.so'))
 lib.GCCStart.argtypes=[ctypes.c_char_p,ctypes.c_int,ctypes.c_char_p,ctypes.c_int];lib.GCCStart.restype=ctypes.c_void_p
 lib.GCCFree.argtypes=[ctypes.c_void_p]
 lib.GCCVersion.restype=ctypes.c_void_p
 for fn in ('GCCProtectedSockets','GCCRejectedSockets'):getattr(lib,fn).restype=ctypes.c_ulonglong
 errors=[];requests=[];protected=[];done=threading.Event()
 listener=socket.socket();listener.bind(('127.0.0.1',0));listener.listen();listener.settimeout(0.1);port=listener.getsockname()[1]
 udp_server=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);udp_server.bind(('127.0.0.1',port));udp_server.settimeout(.1)
 def handle(s):
  try:
   s.settimeout(5);stream=AEADStream(s);data=stream.read()
   while len(data)<7:data+=stream.read()
   assert data[0]==1,'fixture requires literal IPv4';addr=socket.inet_ntoa(data[1:5]);dstport=struct.unpack('!H',data[5:7])[0];body=data[7:]
   requests.append((addr,dstport,'tcp'))
   if (addr,dstport)==('198.18.0.10',18080):
    while b'\r\n\r\n' not in body:body+=stream.read()
    assert body.startswith(b'GET /native-smoke HTTP/1.1\r\n')
    stream.write(b'HTTP/1.1 200 OK\r\nContent-Length: 21\r\nConnection: close\r\n\r\nGCC_NATIVE_TUN_PROOF!')
   elif (addr,dstport)==('1.1.1.1',53):
    while len(body)<2:body+=stream.read()
    n=struct.unpack('!H',body[:2])[0]
    while len(body)<2+n:body+=stream.read()
    q=body[2:2+n];answer=q[:2]+b'\x81\x80'+q[4:6]+b'\x00\x01\x00\x00\x00\x00'+q[12:]+b'\xc0\x0c\x00\x01\x00\x01\x00\x00\x00\x3c\x00\x04\xc6\x33\x64\x07'
    stream.write(struct.pack('!H',len(answer))+answer)
   else:raise AssertionError('unexpected destination '+str((addr,dstport)))
  except Exception as e:errors.append(type(e).__name__+': '+str(e))
  finally:s.close()
 def serve():
  while not done.is_set():
   try:s,_=listener.accept()
   except socket.timeout:continue
   except OSError:return
   threading.Thread(target=handle,args=(s,),daemon=True).start()
 def serve_udp():
  while not done.is_set():
   try:data,peer=udp_server.recvfrom(65535)
   except socket.timeout:continue
   except OSError:return
   try:
    plain=AESGCM(subkey(data[:16])).decrypt(bytes(12),data[16:],None)
    assert plain[:7]==b'\x01\xc6\x12\x00\x0a\x46\xa1';assert plain[7:]==b'GCC_UDP_REQUEST'
    requests.append(('198.18.0.10',18081,'udp'))
    salt=os.urandom(16);out=plain[:7]+b'GCC_UDP_RESPONSE';udp_server.sendto(salt+AESGCM(subkey(salt)).encrypt(bytes(12),out,None),peer)
   except Exception as e:errors.append(type(e).__name__+': '+str(e))
 threading.Thread(target=serve,daemon=True).start();threading.Thread(target=serve_udp,daemon=True).start()
 CALLBACK=ctypes.CFUNCTYPE(ctypes.c_int,ctypes.c_int,ctypes.c_void_p)
 @CALLBACK
 def protect(fd,ctx):protected.append(fd);return 0 if mode=='deny' else 1
 lib.gcc_set_protector.argtypes=[CALLBACK,ctypes.c_void_p];lib.gcc_set_protector(protect,None)
 cfg=json.dumps({'proxies':[{'name':'GCC_SELECTED_PROXY','type':'ss','server':'127.0.0.1','port':port,'cipher':'aes-128-gcm','password':PASSWORD.decode(),'udp':True}],'rules':['MATCH,GCC_SELECTED_PROXY']}).encode()
 left,right=socket.socketpair(socket.AF_UNIX,socket.SOCK_DGRAM);right.settimeout(5)
 home=tempfile.TemporaryDirectory()
 ptr=lib.GCCStart(cfg,len(cfg),home.name.encode(),left.fileno());result=ctypes.string_at(ptr).decode();lib.GCCFree(ptr)
 assert result=='',result
 # GCCStart duplicates the descriptor; ownership remains with this caller.
 os.fstat(left.fileno())
 seq=1000;sport=34567;dest='198.18.0.10'
 right.send(packet('172.19.0.1',dest,sport,18080,seq=seq,flags=2))
 h=parse(right.recv(65535));assert h[0]==6 and h[5]&0x12==0x12,h
 serverseq=h[3]+1;seq+=1
 right.send(packet('172.19.0.1',dest,sport,18080,seq=seq,ack=serverseq))
 req=b'GET /native-smoke HTTP/1.1\r\nHost: fixture.invalid\r\n\r\n'
 right.send(packet('172.19.0.1',dest,sport,18080,seq=seq,ack=serverseq,flags=0x18,payload=req));seq+=len(req)
 if mode=='deny':
  time.sleep(.25);assert not requests,requests;assert lib.GCCRejectedSockets()>0;assert lib.GCCProtectedSockets()==0
 else:
  response=b''
  while b'GCC_NATIVE_TUN_PROOF!' not in response:
   h=parse(right.recv(65535))
   if h[2]!=sport:continue
   if h[6]:response+=h[6];serverseq=h[3]+len(h[6]);right.send(packet('172.19.0.1',dest,sport,18080,seq=seq,ack=serverseq))
  assert b'HTTP/1.1 200 OK' in response
  right.send(packet('172.19.0.1',dest,34568,18081,payload=b'GCC_UDP_REQUEST',udp=True))
  while True:
   h=parse(right.recv(65535))
   if h[0]==17 and h[2]==34568:assert h[6]==b'GCC_UDP_RESPONSE';break
  question=b'\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00\x03gcc\x07invalid\x00\x00\x01\x00\x01'
  right.send(packet('172.19.0.1','172.19.0.2',34569,53,payload=question,udp=True))
  while True:
   h=parse(right.recv(65535))
   if h[0]==17 and h[2]==34569:assert h[6][:2]==b'\x12\x34' and h[6][-4:]==b'\xc6\x33\x64\x07';break
  assert ('1.1.1.1',53,'tcp') in requests
  assert lib.GCCProtectedSockets()>=3,protected
  before=lib.GCCProtectedSockets();before_requests=len(requests)
  right.send(ipv6tcp());right.send(icmp());time.sleep(.15)
  assert lib.GCCProtectedSockets()==before,'IPv6 or ICMP opened an outbound socket'
  assert len(requests)==before_requests,'IPv6 or ICMP reached proxy fixture'

 lib.GCCStop()
 os.fstat(left.fileno())
 ptr=lib.GCCStart(cfg,len(cfg),home.name.encode(),left.fileno());result=ctypes.string_at(ptr).decode();lib.GCCFree(ptr)
 assert result=='PROCESS_RESTART_REQUIRED',result
 assert not errors,errors
 print(json.dumps({'case':mode,'result':'PASS','protected':lib.GCCProtectedSockets(),'rejected':lib.GCCRejectedSockets(),'fixture_destinations':requests,'caller_fd_preserved':True,'restart_requires_fresh_process':True,'ipv6_icmp_no_outbound':True if mode=='success' else 'not_tested'}))
 done.set();listener.close();udp_server.close();left.close();right.close();home.cleanup()
if __name__=='__main__':
 if len(sys.argv)>1:scenario(sys.argv[1])
 else:
  for mode in ('success','deny'):subprocess.run([sys.executable,__file__,mode],check=True,timeout=30)
