# Araştırma projesini paylaşma kılavuzu

Bir **QuPath gezinme-kaydı araştırma projesini** (sessiz, anonim odak/gezinme kaydı) katılımcı
araştırmacılara nasıl dağıtacağınızı ve verilerini geri alacağınızı anlatır — uzamsal · zamansal ·
yönsel inceleme araştırması için. İki bölüm: bir **araştırmacı hızlı kılavuzu** (projeyi açan kişiye
verin) ve bir **koordinatör kılavuzu** (projeyi hazırlayıp gönderen için).

> **"QuPath yeterli mi, eklenti de gerekli mi?" kısa yanıt — İkisi de gerekli.** Yalnız QuPath
> yetmez. `qupath-extension-atlas` eklentisi (1) atlas slaytlarını açan Deep-Zoom okuyucuyu ve
> (2) kayıt + onay mekanizmasının tamamını sağlar. Eklenti olmadan atlas slaytları görüntülenmez ve
> kayıt bayrağı hiçbir şey yapmaz.

*(English version: [SHARING.md](SHARING.md).)*

---

## Araştırmacı için (projeyi alan kişi)

### 1. QuPath + eklentiyi kurun — projeyi açmadan **önce**

1. **QuPath 0.6 veya üstü** — [qupath.github.io](https://qupath.github.io) adresinden indirin (Java 21).
2. **Eklenti** (zorunlu — hem slaytları açar hem kaydı çalıştırır). En kolayı, koordinatör JAR'ı
   pakete koyduysa:
   - **Pakete gömülü JAR (en basit):** paketteki **`qupath-extension-atlas-<sürüm>.jar`** dosyasını
     QuPath penceresine sürükleyin → kurulumu onaylayın → **QuPath'i yeniden başlatın.** Bu adım için
     internet veya katalog gerekmez.
   - *Veya* **Katalog:** QuPath → `Extensions ▸ Manage extension catalogs ▸ Add` →
     `https://github.com/sbalci/patolojiatlasi-QuPath` → sonra `Extensions ▸ Manage extensions ▸
     Install` → yeniden başlatın.
   - *Veya* JAR'ı projenin GitHub *Releases* bölümünden indirip yukarıdaki gibi sürükleyin.
3. **İnternet** — yalnızca proje **atlas** slaytları kullanıyorsa gerekir (slaytlar
   `images.patolojiatlasi.com` üzerinden akış olarak gelir); önceden indirilecek bir şey yoktur.
   Yerel slaytlı projeler internet gerektirmez.

> ⚠️ **Eklentiyi önce kurun.** Projeyi eklenti olmadan açarsanız hiçbir şey bozulmaz — ama slaytlar
> görüntülenmez ("unable to build server" iletisi), kayıt çalışmaz ve oturum boşa gider. Eklentiyi
> kurun, yeniden açın; sorunsuz çalışır.

### 2. Projeyi açın

1. Proje klasörünü kararlı bir yere açın (ör. Belgeler). İçindeki dosyaları dışarı taşımayın
   (`project.qpproj`, `data/`, `atlas-research.json`, …).
2. QuPath → `File ▸ Project… ▸ Open project…` → **`project.qpproj`** seçin.
3. İlk açılışta bir kez **"Araştırma kaydı"** bildirimi görebilirsiniz: kabul ederseniz baktığınız
   bölgeler ve süre **anonim ve sessizce** kaydedilir (kimlik yok, ekranda hiçbir şey gösterilmez).
   Katılmak için **Tamam**'a basın. *(Bazı çalışmalar onayı kayıt sırasında ayrıca alır; o durumda
   bildirim görünmez — koordinatörünüze sorun.)*
4. **Bir slayt açılmazsa** ve proje **kendi yerel slaytlarınızı** kullanıyorsa: QuPath dosyayı
   yeniden bağlamayı önerir — koordinatörün **ayrıca** gönderdiği slayt dosyalarını kaydettiğiniz
   klasörü gösterin. Atlas slaytları yeniden bağlama gerektirmez (URL'den akar).

### 3. Slaytları normal şekilde inceleyin

Her zamanki gibi kaydırın, yakınlaştırın, slaytlar arasında gezin. **Her şey otomatik ve sessizce
kaydedilir** — basılacak düğme yoktur, ısı haritası gösterilmez (bu bilinçlidir: nereye baktığınızı
görmek nasıl baktığınızı değiştirir). Doğal çalışın.

> ⚠️ **Çalışmanız anotasyon çizmenizi istiyorsa,** çizdiğiniz anotasyon şekilleri *ve üzerlerine
> yazdığınız not/yorum metni* geri gönderilen verinize kaydedilir. Anotasyon notuna **kimlik
> belirten bir bilgi yazmayın** (hasta adı, protokol/dosya numarası) — geometri anonimdir, ancak
> yazdığınız serbest metin olduğu gibi saklanır.

### 4. Verinizi geri gönderin

Bitirdiğinizde (projeyi kapatın veya QuPath'ten çıkın), eklenti proje klasörüne **tek bir dosya**
yazar:

```
<proje klasörünüz>/atlas-focus_<tarih-saat>_<kimlik>.zip
```

**O tek zip'i koordinatöre e-postayla gönderin.** (Emin değilseniz tüm `atlas-focus/` alt klasörünü
gönderin — hepsini içerir.) **Anonimdir** — ad yok, rastgele oturum kimliği ve yalnızca tarih — güvenle
gönderilebilir. Bu *veri* zip'i, aldığınız *proje* zip'inden **farklı bir dosyadır.**

### Sık sorulanlar

- **Eklenti gerçekten gerekli mi?** Evet — yukarıya bakın. Yalnız QuPath slaytları açamaz ve kayıt yapamaz.
- **Slayt yüklenmiyor.** Atlas slaytı → internetinizi kontrol edin. Kendi slaytınız → yeniden bağlayın (adım 2.4).
- **Verim nerede?** Proje klasöründe: `atlas-focus/` ve `atlas-focus_*.zip`.
- **Anonim mi?** Evet — ad yok, rastgele kimlik, yalnızca tarih.
- **"Kaydederken" hiçbir şey olmuyor.** Doğru — tasarım gereği gizli; kayıt görünmezdir.
- **Kaydı kendim kontrol edebilir/açıp kapatabilir miyim?** Normalde gerekmez — proje sizin için
  başlatır. İsteğe bağlı elle geçiş: **Extensions ▸ Araştırma ▸ Odak ısı haritası ▸ Gezinme kaydı
  (araştırma)** (ör. açık olduğunu doğrulamak için).

---

## Koordinatör için (projeyi hazırlayıp gönderen)

### Gönderilecek paket

Dağıttığınız zip'e şunları koyun (küçük tutun — aşağıdaki slayt notlarına bakın):

- **Proje klasörü** (`project.qpproj`, `data/`, `atlas-research.json`).
- **Eklenti JAR'ı** `qupath-extension-atlas-<sürüm>.jar` (alıcılar sadece sürükleyip bırakır —
  katalog/internet kurulumu gerekmez ve sürüm garantili uyumlu olur). MIT lisanslı, yeniden dağıtılabilir.
- Kısa bir **`README-first.txt`** (şablonu bu dosyanın sonunda).

### Slaytlar — proje zip'ini küçük tutun

- **Atlas slaytları (bugün çalışır):** projeyi atlas tarayıcısından oluşturun
  (`Extensions ▸ Patoloji Atlası ▸ Slaytlara gözat… ▸ Create project…`) ve **"Araştırma projesi —
  gezinme kaydı (blinded)"** kutusunu işaretleyin. Girdiler **DZI URL**'leridir; proje zip'i çok
  küçük olur ve slaytlar alıcının makinesinde akış olarak gelir (eklenti + internet gerekir).
- **Kendi yerel slaytlarınız (SVS vb.):** proje oluşturucu yalnızca atlas içindir; bu yüzden
  slaytlarınızdan **normal bir QuPath projesi** kurun, sonra iki yoldan biriyle araştırma projesi
  olarak işaretleyin:
  - **Tek tık (önerilen):** proje açıkken **Extensions ▸ Araştırma ▸ "Mevcut projeyi araştırma
    projesi yap (gezinme kaydı)…"** sidecar'ı sizin için yazar, bir kez onaylar ve bu oturumda da
    kaydı hemen başlatır.
  - **Elle:** proje klasörüne kendiniz bir `atlas-research.json` dosyası ekleyin:
    ```json
    { "schema": "atlas-research/1", "blindedTracking": true, "consented": false }
    ```
  Her iki yolda da eklentinin proje-açılış kancası sidecar'ı slayt kaynağından bağımsız okur; kayıt
  her açılışta başlar. **Slayt dosyalarını ayrıca gönderin** (büyüktürler — projeye sıkıştırmak
  pratik değildir); alıcılar açarken yeniden bağlar.

### Onay modeli — ve gözlemci etkisi dengesi (mutlaka okuyun)

Bir okuyucuya, tam işe başlarken izlendiğini söylemek **nasıl baktığını değiştirebilir**
(tepkisellik / Hawthorne etkisi) — bu da tarafsız tasarımı zayıflatır. Bilinçli seçin:

- **(A) Kayıtta onay + sessiz kayıt — geçerlilik için önerilen.** Yazılı bilgilendirilmiş onayı
  **kayıt anında bir kez** alın (etik kurul onaylı; genel ifade, ör. "slayt yazılımını kullanımınız
  anonim olarak kaydedilebilir"), sonra `atlas-research.json` içinde **`"consented": true`** ile
  gönderin ki **uygulama içi bildirim çıkmasın** ve davranış doğal kalsın. Tepkiselliği ayrıca
  azaltın: genel çerçeveleme (ölçtüğünüz değişkeni — bakış/süre — adlandırmak zorunda değilsiniz;
  etik kurullar, bilgilendirmeyle birlikte eksik-ama-aldatıcı-olmayan açıklamaya izin verir), **doğal
  görev** (gerçek tanı/eğitim işi) ve **alışma** (ilk birkaç ısınma oturumunu dışarıda bırakın).
  Sonrasında katılımcıları bilgilendirin ve veriyi geri çekme seçeneği sunun.
- **(B) Uygulama içi oturum onayı.** **`"consented": false`** ile gönderin; her okuyucu ilk açılışta
  bir kez bildirimi görür. Dijital onay kaydı olarak en basit, ama en çok yönlendirici olan.
- **Yararlı gerçek:** **karşılaştırmalı** sorularda (uzman vs asistan, koşul A vs B) gözlemci etkisi
  gruplar arasında ortak bir sabittir ve **büyük ölçüde birbirini götürür** — bir miktar tepkisellikle
  bile karşılaştırmalar geçerli kalır.

> ⚠️ **Onay tuzağı.** Projeyi *siz* kurup test ederken onay bildirimi **sizin** QuPath'inizde çıkar.
> **Tamam**'a basarsanız `consented:true` yazılır ve korunur. Bu yüzden bilinçli karar verin: (B) için
> zip'lemeden **önce** bildirimi **İptal** edin (veya `"consented": false` yapın); (A) için `true`
> bırakmak bilinçlidir. Yanlış değeri gönderirseniz ya herkes bildirimsiz kaydedilir (B istenmişse) ya
> da istemediğiniz halde herkese sorulur (A istenmişse).

> **Bu bir etik/etik-kurul kararıdır.** Eksik-açıklama + sonradan bilgilendirme tasarımı kurulunuzca
> onaylanmalıdır. Bu kılavuz standart yöntemi anlatır; seçiminizi dayatmaz.

### Veriyi geri alma ve çözümleme

- Alıcılar `atlas-focus_*.zip` dosyalarını (anonim) geri gönderir. **`sessionId → katılımcı`
  eşlemesini uygulama dışında tutun** (üçüncü bir tarafta ayrı bir dosya) — **çift kör** olmasını
  sağlayan budur: çözümleyen kişi kimliğe (ve koşul kodu kullanırsanız gruba) kör çalışır.
- Geri gelen zip'leri **[`analysis/`](analysis/README.md)** araçlarıyla çözümleyin:
  `analysis/python/` (numpy/pandas/matplotlib/scipy) veya `analysis/R/` (jsonlite/ggplot2/irr) —
  kişi-başı ölçütler, okuyucular-arası uyum/konsensüs, referans (uzman/ROI) karşılaştırması, gezinme
  yolu; hızlı bakış için `tools/quicklook-blinded-focus.py`. Etiketlerde ad değil **koşul kodu**
  (`--labels sessionId,label`) kullanın.
- Kayıt üç ekseni yakalar: **uzamsal** (odak ızgarası), **zamansal** (bekleme-ms + toplam süre) ve
  **yönsel** (sıralı gezinme yolu, şema/3).

---

## `README-first.txt` — her zip'e koyun

```
QuPath gezinme-kaydı araştırma projesi — hızlı başlangıç

1. QuPath 0.6+ kurun  (https://qupath.github.io).
2. Eklentiyi kurun: qupath-extension-atlas-<sürüm>.jar dosyasını QuPath
   penceresine sürükleyin, onaylayın ve QuPath'i YENİDEN BAŞLATIN.
   (Bunu 3. adımdan ÖNCE yapın.)
3. QuPath'te: File > Project... > Open project... > project.qpproj
   - "Araştırma kaydı" bildirimi çıkarsa kabul edin.
   - Atlas slaytları internetten akar; yerel slaytlarda sorulunca yeniden bağlayın.
4. Slaytları normal inceleyin. Kayıt otomatik, sessiz ve anonimdir.
5. Bitince proje klasöründeki  atlas-focus_<tarih>_<kimlik>.zip  dosyasını
   çalışma koordinatörüne geri gönderin.

Sorular: SHARING.tr.md (veya İngilizce SHARING.md).
```
