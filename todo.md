# TODO — WalkieTalkiePlugin

Poniżej jest lista prac w kolejności wdrażania (MVP → dodatki). Aktualizujemy ją w trakcie.

## 1) Specyfikacja i decyzje (blokery)
- [x] Ustalone: drużyny tylko przez permisje
- [x] Ustalone: hold PPM to talk
- [x] Ustalone: receptury shaped
- [x] Ustalenie: identyfikacja itemów (ItemsAdder id vs PDC)

## 2) Integracja Simple Voice Chat (SVC)
- [x] Dodać zależność do SVC API w Gradle (compileOnly)
- [x] Wykryć brak SVC i wyłączyć funkcje (log warning)
- [x] Zaimplementować wysyłanie audio jako “direct” (bez prox) do listy odbiorców
- [x] Cleanup stanów: quit/kick/disable/zmiana slotu żeby nie było bugów

## 3) Model kanałów i permisji
- [x] Zdefiniować kanały: czerwoni/niebiescy/handlarze/piraci/tohandlarze
- [x] Zdefiniować permisje craft/use/listen + randomChannel
- [x] Zaimplementować sprawdzanie permisji (use/listen) w runtime

## 4) Integracja ItemsAdder
- [x] Konfigurowalne identyfikatory itemów (config.yml)
- [x] Funkcja rozpoznawania typu radia z itemstack

## 5) Eventy Minecraft (Paper)
- [x] Blokada wrzucania radii do offhand (InventoryClickEvent + swap hotkey)
- [x] Wykrycie PPM z radiem w main hand (PlayerInteractEvent)
- [x] Wymóg hotbaru dla słuchania (check 9 slotów, bez offhand)

## 6) Craftingi
- [x] Receptury shaped: 4 żelaza + redstone + wełna (kolor zależny od radia)
- [x] Osobna receptura dla radia “tohandlarze” (fioletowa wełna)
- [x] Osobna receptura dla czarnego radia piratów (czarna wełna)
- [x] Brak permisji `radio.craft.*` => brak craftingu

## 7) Podsłuch piratów
- [x] “Czarne stare radio”: tylko podczas trzymania PPM
- [x] Losowy wybór kanału rozmowy do podsłuchu
- [x] Permisja `radio.randomChannel.piraci`

## 8) Testy i weryfikacja
- [ ] Test manualny: 2 graczy, 2 kanały, brak prox
- [ ] Test manualny: hotbar requirement + offhand block
- [ ] Test manualny: permissive/deny scenariusze

## 9) Dokumentacja
- [ ] README: instalacja, zależności, permisje, konfiguracja
- [ ] Przykładowa konfiguracja ItemsAdder (opcjonalnie)
