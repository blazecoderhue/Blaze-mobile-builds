package com.blaze.assistant.browser

import org.json.JSONObject

/**
 * Location masking: a script that runs before any page script, in every frame, so pages can't learn
 * where the phone really is.
 *  - WebRTC is removed (it would reveal the real IP around the proxy).
 *  - Geolocation reports the VPN country's capital (a few km of jitter), or is denied with no VPN.
 *  - Time zone and language report that country too (Intl, Date offsets, navigator.language).
 * Pure Kotlin so it's unit-tested.
 */
object LocationMask {
    fun script(c: Countries.Country?, seed: Long = System.nanoTime()): String {
        val cfg = JSONObject()
        if (c != null) {
            val r = java.util.Random(seed)
            cfg.put("lat", c.lat + (r.nextDouble() - 0.5) * 0.06).put("lon", c.lon + (r.nextDouble() - 0.5) * 0.06)
                .put("tz", c.tz).put("lang", c.lang)
        }
        return "(function(){var C=$cfg;$BODY})();"
    }

    private const val BODY = """
try{if(window.__bzMask)return;Object.defineProperty(window,'__bzMask',{value:1});}catch(e){return;}
function def(o,k,v){try{Object.defineProperty(o,k,{get:function(){return v;},configurable:true});}catch(e){}}
['RTCPeerConnection','webkitRTCPeerConnection','RTCDataChannel','RTCSessionDescription','RTCIceCandidate'].forEach(function(k){try{delete window[k];}catch(e){}def(window,k,undefined);});
try{if(navigator.mediaDevices){def(navigator.mediaDevices,'getUserMedia',undefined);}}catch(e){}
var G={};
if(C.lat!==undefined){
  var pos=function(){return {coords:{latitude:C.lat,longitude:C.lon,accuracy:1200+Math.random()*800,altitude:null,altitudeAccuracy:null,heading:null,speed:null},timestamp:Date.now()};};
  G.getCurrentPosition=function(ok){setTimeout(function(){ok&&ok(pos());},150+Math.random()*300);};
  G.watchPosition=function(ok){var id=Math.floor(Math.random()*1e6);setTimeout(function(){ok&&ok(pos());},200);return id;};
}else{
  var no=function(ok,err){setTimeout(function(){err&&err({code:1,PERMISSION_DENIED:1,POSITION_UNAVAILABLE:2,TIMEOUT:3,message:'User denied Geolocation'});},100);};
  G.getCurrentPosition=no;G.watchPosition=function(ok,err){no(ok,err);return 0;};
}
G.clearWatch=function(){};
def(Navigator.prototype,'geolocation',G);
if(C.tz){
  var DTF=Intl.DateTimeFormat;
  var Wrapped=function(l,o){o=Object.assign({},o||{});if(!o.timeZone)o.timeZone=C.tz;return new DTF(l,o);};
  Wrapped.prototype=DTF.prototype;Wrapped.supportedLocalesOf=DTF.supportedLocalesOf;
  try{Intl.DateTimeFormat=Wrapped;}catch(e){}
  var fmt=new DTF('en-US',{timeZone:C.tz,hourCycle:'h23',year:'numeric',month:'numeric',day:'numeric',hour:'numeric',minute:'numeric',second:'numeric'});
  var off=function(d){var t=d.getTime();if(isNaN(t))return NaN;var p={};fmt.formatToParts(d).forEach(function(x){p[x.type]=x.value;});
    var u=Date.UTC(+p.year,+p.month-1,+p.day,(+p.hour)%24,+p.minute,+p.second);return Math.round((Math.floor(t/1000)*1000-u)/60000);};
  Date.prototype.getTimezoneOffset=function(){return off(this);};
  ['toLocaleString','toLocaleDateString','toLocaleTimeString'].forEach(function(k){var f=Date.prototype[k];
    Date.prototype[k]=function(l,o){o=Object.assign({},o||{});if(!o.timeZone)o.timeZone=C.tz;return f.call(this,l,o);};});
}
if(C.lang){def(Navigator.prototype,'language',C.lang);def(Navigator.prototype,'languages',Object.freeze([C.lang,C.lang.split('-')[0]]));}
"""
}
