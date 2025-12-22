# PRD — WalkieTalkiePlugin (Simple Voice Chat) — 1.21.8

## 1) Cel produktu
Plugin dodaje “krótkofalówki” (radia) jako przedmioty, które umożliwiają komunikację głosową **tylko między graczami posiadającymi ten sam typ radia**.
Komunikacja radiowa:
- **nie emituje dźwięku dookoła** (brak prox chat),
- jest “od gracza do graczy” (direct),
- działa przez integrację z **Simple Voice Chat** (SVC) na **Minecraft 1.21.8**.

Założenie: przynależność do “drużyny” jest realizowana wyłącznie przez **permisje** (brak integracji ze scoreboard/innym systemem drużyn).

## 2) Zakres

### 2.1 Drużyny i typy radia (bazowe)
Są 4 drużyny, a plugin ma 4 podstawowe typy radia do rozmów w obrębie drużyn:
- Czerwoni (kolor czerwony)
- Niebiescy (kolor niebieski)
- Handlarze (kolor żółty)
- Piraci (kolor zielony)

### 2.2 Dodatkowe radia (specjalne)
1) **Specjalne radio do rozmów z grupą Handlarzy** (kolor fioletowy)
- Działa identycznie jak zwykłe radia (PTT na PPM, direct voice do posiadaczy tego radia),
- Ma **oddzielny crafting i oddzielne permisje** (np. `radio.use.tohandlarze`).

2) **Czarne stare radio piratów (podsłuch)**
- Piraci posiadają czarne stare radio, które **podsłuchuje losowe rozmowy**.
- Warunek: muszą je trzymać w ręce i **trzymać PPM** aby podsłuchiwać.
- Permisja: `radio.randomChannel.piraci`.

## 3) Integracje

### 3.1 Simple Voice Chat
- Plugin jest rozszerzeniem do SVC i korzysta z jego API.
- Komunikacja ma być transmitowana poprzez SVC tak, aby:
  - nadawca nie był słyszany w okolicy,
  - odbiorcy słyszeli go tylko przez “kanał radiowy” (direct).

### 3.2 ItemsAdder
- Przedmioty radia będą dostarczone przez ItemsAdder.
- Plugin musi umieć rozpoznawać, że item w ręce to dane radio (np. przez custom model / namespace / persistent data / lore / id ItemsAdder).
- Nazwy (propozycja):
  - Radio Czerwonych
  - Radio Niebieskich
  - Radio Handlarzy
  - Radio Piratów
  - Radio “Do Handlarzy” (fioletowe)
  - Stare Czarne Radio Piratów (podsłuch)

## 4) UX / zachowanie w grze

### 4.1 Nadawanie (push-to-talk na PPM)
- Nadawanie działa, gdy gracz:
  - trzyma odpowiednie radio w **prawej ręce (main hand)**,
  - **trzyma PPM** (hold-to-talk) (lub w przypadku podsłuchu: trzyma PPM — patrz 4.3).

Wymaganie jakościowe: obsługa musi być odporna na edge-case’y (wyjście z serwera, kick, disable pluginu, zmiana slotu itp.), tak aby nie zostawał “zawieszony” stan nadawania/odbioru.

### 4.2 Odbiór
- Odbiorca usłyszy radio tylko jeśli:
  - ma odpowiednią permisję “listen” dla kanału,
  - posiada dane radio **w hotbarze** (jeden z 9 slotów),
  - radio **nie jest w left hand** (left hand nie liczy się do warunku posiadania i jest zakazane).

### 4.3 Left hand (zakazy)
Wymaganie: “do lewej zakaz wrzucania na slot”.
- Plugin ma uniemożliwiać przeniesienie radia do offhand.
- Jeśli gracz spróbuje przenieść radio do offhand (klikiem, swapem, hotkeyem) — akcja jest anulowana.

### 4.4 Ograniczenie używania “cudzego” radia
- Gracz może używać tylko radia, do którego ma permisję `radio.use.<druzyna>`.
- Dodatkowo ma być “prevent”: np. Czerwoni nie mogą używać radia Niebieskich.
  - Rozumiemy to jako: brak uprawnień lub jawna blokada.
  - Minimalny wariant: brak wymaganej permisji = brak użycia.

### 4.5 Kanały
- Każdy typ radia odpowiada jednemu kanałowi radiowemu.
- Nadawanie trafia tylko do graczy z tym samym radiem + spełniających warunki odbioru.

## 5) Craftingi

### 5.1 Receptury
- Każde radio ma osobny crafting.
- Receptury różnią się kolorem wełny zgodnie z drużyną:
  - Czerwoni: czerwona wełna
  - Niebiescy: niebieska wełna
  - Handlarze: żółta wełna
  - Piraci: zielona wełna
  - “Do Handlarzy” (specjalne): fioletowa wełna
  - Stare czarne radio piratów: czarna wełna (propozycja)

Ustalona receptura (shaped): **4 żelaza + redstone + wełna w kolorze kanału**.

### 5.2 Permisje craftingu
- Każdy crafting jest osobno kontrolowany permisją, np.:
  - `radio.craft.czerwoni`
  - `radio.craft.niebiescy`
  - `radio.craft.handlarze`
  - `radio.craft.piraci`
  - `radio.craft.tohandlarze`
  - `radio.craft.randomchannel.piraci` (dla czarnego radia podsłuchu — nazwa do ustalenia)

## 6) Permisje (proponowana specyfikacja)

### 6.1 Użycie (nadawanie)
- `radio.use.czerwoni`
- `radio.use.niebiescy`
- `radio.use.handlarze`
- `radio.use.piraci`
- `radio.use.tohandlarze` (radio fioletowe)

### 6.2 Słuchanie (odbiór)
- `radio.listen.czerwoni`
- `radio.listen.niebiescy`
- `radio.listen.handlarze`
- `radio.listen.piraci`
- `radio.listen.tohandlarze`

### 6.3 Podsłuch piratów
- `radio.randomChannel.piraci`

### 6.4 Blokady / prevent
Wymóg: “permisja i prevent do uniemożliwienia używania radia”.
- Minimalne wdrożenie: jeżeli brak `radio.use.<kanał>` to nie działa.
- Opcjonalne rozszerzenie: explicite deny np. `radio.deny.use.<kanał>` (do potwierdzenia).

## 7) Konfiguracja
- Konfiguracja identyfikatorów ItemsAdder dla poszczególnych radio-itemów (aby można było zmienić id bez rekompilacji).
- Konfiguracja receptur (shape + składniki) oraz ich włączanie/wyłączanie.
- (Jeśli potrzebne przez SVC) konfiguracja portu/transportu lub parametrów integracji.

Uwaga: Voice Chat działa na oddzielnym porcie (ustawionym po stronie SVC/serwera), ale plugin integruje się z SVC głównie przez API wtyczki.

## 8) Ograniczenia niefunkcjonalne
- Wydajność: nie skanować całego ekwipunku per tick; warunek hotbaru sprawdzać na eventach lub w sposób lekki.
- Kompatybilność: Paper/Spigot na 1.21.8 + Simple Voice Chat na 1.21.8.

## 9) Kryteria akceptacji (MVP)
1) Działa 5 kanałów rozmów (4 drużynowe + 1 fioletowy “do handlarzy”) jako direct voice bez prox.
2) Nadawanie działa tylko z main hand i na PPM.
3) Odbiór działa tylko gdy radio jest w hotbarze (bez offhand).
4) Offhand: nie da się włożyć radia do left hand.
5) Permisje:
   - craft: brak permisji => brak craftingu,
   - use: brak permisji => brak nadawania,
   - listen: brak permisji => brak odbioru.
6) Pirackie czarne radio podsłuchu: działa tylko podczas trzymania PPM i wybiera losowy kanał do podsłuchu.

## 10) Pytania / niejasności do doprecyzowania
Pozostałe pytania (jeśli chcesz doprecyzować przed kodem, ale nie blokują MVP):
1) Czy “radio w hotbarze” dla odbioru oznacza: wystarczy mieć w dowolnym slocie hotbaru, nawet jeśli aktualnie trzymasz coś innego? (Domyślnie: tak.)
2) Identyfikacja ItemsAdder: wolisz po ItemsAdder API (CustomStack/id), czy po PDC tagu nadawanym przy craftingu?
