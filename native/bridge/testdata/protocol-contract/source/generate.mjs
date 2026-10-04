import fs from 'node:fs';
import { generateClashSubscription, generateMihomoRenderTemplate, generateClashProviderSubscription } from './clash-subscription.mjs';
const common = {host:'192.0.2.10',port:443};
const fixtures = {
  'ss-aes192': {...common,name:'Synthetic SS AES192',protocol:'ss',method:'aes-192-gcm',password:'synthetic-only'},
  'vless-tls': {...common,name:'Synthetic VLESS TLS',protocol:'vless',uuid:'11111111-1111-4111-8111-111111111111',tls:true,serverName:'fixture.example.invalid',network:'tcp',udp:false},
  'hysteria2-bounded-options': {...common,name:'Synthetic HY2 bounded options',protocol:'hysteria2',password:'synthetic-only',sni:'fixture.example.invalid',up:'100 Mbps',down:'200 Mbps',obfs:'salamander',obfsPassword:'synthetic-obfs-only',alpn:['h3']},
  'tuic-v5-bounded-options': {...common,name:'Synthetic TUIC bounded options',protocol:'tuic',uuid:'22222222-2222-4222-8222-222222222222',password:'synthetic-only',sni:'fixture.example.invalid',congestionController:'bbr',udpRelayMode:'quic',alpn:['h3']},
  'ss-minimal': {...common,name:'Synthetic SS',protocol:'ss',method:'aes-128-gcm',password:'synthetic-only'},
  'vless-reality': {...common,name:'Synthetic Reality',protocol:'vless',uuid:'11111111-1111-4111-8111-111111111111',udp:true,tls:true,network:'tcp',flow:'xtls-rprx-vision',serverName:'fixture.example.invalid',clientFingerprint:'chrome',packetEncoding:'xudp',realityOpts:{publicKey:'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE',shortId:'0123456789abcdef'}},
  'vless-defaults': {...common,name:'Synthetic VLESS defaults',protocol:'vless',uuid:'11111111-1111-4111-8111-111111111111'},
  'hysteria2-minimal': {...common,name:'Synthetic HY2',protocol:'hysteria2',password:'synthetic-only',sni:'fixture.example.invalid',skipCertVerify:false,alpn:['h3']},
  'hysteria2-options': {...common,name:'Synthetic HY2 options',protocol:'hysteria2',password:'synthetic-only',sni:'fixture.example.invalid',ports:'443,8443-8445',hopInterval:30,up:'100 Mbps',down:'200 Mbps',obfs:'salamander',obfsPassword:'synthetic-obfs-only',skipCertVerify:false,fingerprint:'11'.repeat(32),alpn:['h3'],bbrProfile:'standard'},
  'tuic-v5-minimal': {...common,name:'Synthetic TUIC',protocol:'tuic',uuid:'22222222-2222-4222-8222-222222222222',password:'synthetic-only',sni:'fixture.example.invalid',skipCertVerify:false},
  'tuic-v5-options': {...common,name:'Synthetic TUIC options',protocol:'tuic',uuid:'22222222-2222-4222-8222-222222222222',password:'synthetic-only',sni:'fixture.example.invalid',udpRelayMode:'native',congestionController:'bbr',bbrProfile:'standard',alpn:['h3'],disableSni:false,reduceRtt:false,requestTimeout:8000,heartbeatInterval:10000,maxUdpRelayPacketSize:1252,fastOpen:false,skipCertVerify:false,maxOpenStreams:100},
  'tuic-token-ambiguous': {...common,name:'Synthetic legacy TUIC',protocol:'tuic',uuid:'22222222-2222-4222-8222-222222222222',password:'synthetic-only',token:'synthetic-legacy-token',disableSni:true},
  'ss-hostname': {...common,name:'Synthetic hostname',host:'node.example.invalid',protocol:'ss',method:'aes-128-gcm',password:'synthetic-only'},
};
for (const profile of ['conservative', 'standard', 'aggressive']) {
  fixtures[`hysteria2-bbr-${profile}`] = {...fixtures['hysteria2-minimal'],name:`Synthetic HY2 BBR ${profile}`,bbrProfile:profile};
  fixtures[`tuic-v5-bbr-${profile}`] = {...fixtures['tuic-v5-minimal'],name:`Synthetic TUIC BBR ${profile}`,congestionController:'bbr',bbrProfile:profile};
}
fixtures['tuic-v5-cubic-bbr-profile'] = {...fixtures['tuic-v5-minimal'],name:'Synthetic TUIC cubic BBR profile',congestionController:'cubic',bbrProfile:'aggressive'};
fixtures['tuic-v5-default-bbr-profile'] = {...fixtures['tuic-v5-minimal'],name:'Synthetic TUIC default BBR profile',bbrProfile:'standard'};
fs.mkdirSync(new URL('../renderer/',import.meta.url),{recursive:true});
for(const [name,server] of Object.entries(fixtures)) {
  const input={servers:[server]};
  fs.writeFileSync(new URL(`../renderer/${name}.input.json`,import.meta.url),JSON.stringify(input,null,2)+'\n');
  fs.writeFileSync(new URL(`../renderer/${name}.clash.yaml`,import.meta.url),generateClashSubscription(input));
  fs.writeFileSync(new URL(`../renderer/${name}.mihomo.yaml`,import.meta.url),generateMihomoRenderTemplate(input));
}
const mixed={servers:['ss-minimal','vless-reality','hysteria2-minimal','tuic-v5-minimal'].map(k=>fixtures[k])};
fs.writeFileSync(new URL('../renderer/mixed.clash.yaml',import.meta.url),generateClashSubscription(mixed));
fs.writeFileSync(new URL('../renderer/mixed.mihomo.yaml',import.meta.url),generateMihomoRenderTemplate(mixed));
fs.writeFileSync(new URL('../renderer/provider-only.clash.yaml',import.meta.url),generateClashProviderSubscription(mixed,{providerUrl:'https://fixtures.example.invalid/provider.yaml'}));
console.log('Generated renderer-only synthetic fixtures; no network or protocol sessions');
