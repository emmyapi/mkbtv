# Seasonal recommendations on Home

The calendar selects the current theme in **Europe/Copenhagen**. For each profile, aim for **16 distinct titles** in one shelf, with up to eight editorial choices and up to eight movies found dynamically from the eight original references. Show the current theme with however many eligible titles remain, down to its last film. When the supply is exhausted, hide the shelf until the calendar schedules the next theme.

Across the 27 themes, 214 of the 216 opening selections are movies. The two deliberate whole-series choices are *Borgen* and the 1991 Danish *The Julekalender*. The TV opens the ordinary movie or series details page. Episodes, individual seasons, specials attached to a series and trailers are ineligible.

## What viewers see

Show at most one seasonal shelf immediately below **Continue Watching**, or in the first catalog position when that row is absent. References span genres and decades, including recent releases and favorites that millennials and Gen Z grew up with. Rank by thematic connection and watch history without inferring anyone's age. Give it an English heading, a restrained accent and an optional icon. Use English UI copy and official English titles where available. Choose membership first, then give all visible cards one display order based on relevance to the theme and profile, freshness and variety. Either type can lead or occupy several consecutive positions. Preserve the displayed order during a visit and use the previous order to break close ties on return. Keep the shelf's identity stable across the year and respect a profile's saved hide or reorder choice. Opening details leaves watch progress intact.

## How the shelf understands its theme

Every theme below supplies eight editorial examples, two editorial reserves, a calendar window and a rule describing what earns a place. The original eight are the **persistent reference set** even after some leave a profile's visible shelf. At catalog preparation, resolve each title, type, edition and year to canonical provider IDs. Annotate the examples with the particular story, setting, format or factual constraint that connects them to this theme. For example, *Jaws* teaches **the July Fourth beach holiday** on the Independence Day shelf. Its shark teaches nothing about that occasion.

1. **Retrieve from the eight.** Query related movies for each reference. Also search new worldwide releases and the wider catalog using the examples' theme-relevant traits. This second path lets a new movie enter before providers have accumulated votes or recommendation links. Alternate source and seed when gathering candidates. Keep which seed contributed which signal. Store the theme relationships independently of current posters, per-profile watch history and shelf occurrence. Removing a poster only changes what is shown. The eight references keep their full editorial influence across refills and future years.
   Keep a record of previously shown discoveries and their verified connections to the eight. They remain usable as secondary lookup sources after rotation. A movie that this profile actually watched can additionally influence ranking for this profile, including across annual windows. Mere display or rotation is neutral. Keep presentation exclusions separate from reference lookup and suppress a personal boost if the watch is undone or the viewer rejected that title. Every new result must still pass the original theme and seed checks, however many replacements occurred.
2. **Qualify the movie twice.** Require evidence that it meets the shelf's written rule and a specific connection to at least one of the original eight. For Christmas that might be a documented Christmas plot and the family-reunion premise shared with *Home Alone*. For the Oscars, verify the nomination and that year's different winner, then match a storytelling trait of a seed. A sequel, shared actor, generic genre or similar poster alone cannot pass. A release in the right month is insufficient. A constrained theme may use a documented franchise or subject connection. Two copies of one synopsis count once. Allow an editor to exclude a false match by ID.
3. **Rank for this profile.** Apply profile exclusions, worldwide release eligibility, known watch history and canonical deduplication first. Rank by thematic fit, number and strength of independent seed connections, coverage of distinct seed traits, then freshness and profile affinity. Break ties deterministically by canonical ID. As a diversity target, cover at least three different reference titles across the eight dynamic picks when possible. Normally cap a franchise at two visible titles, with an explicit exception for *May the Fourth*. Keep at least one place among the dynamic eight for a qualifying release from the last 90 days when available. Low vote counts are expected for premieres.
4. **Keep the explanation.** Record target ID, editorial or dynamic slot, reference IDs, matched traits, title-level evidence, release evidence and why the next alternative lost. On TV an optional English reason should describe the connection in plain language, for example **Christmas through a child's eyes**. Keep plot twists out of reasons.

## What happens when someone watches a title

| Event on this profile | What changes at the next Home refresh |
|---|---|
| A dynamic movie is completed or explicitly marked watched | Remove it and promote the next eligible unseen movie from the precomputed pool into its dynamic place. Evaluate the replacement against the original eight even after repeated refills. Record its theme connections for future retrieval and its watch signal for this profile. If no replacement qualifies, remove the watched card and show the remaining titles. Keep their order stable during the visit. |
| An editorial choice is completed or marked watched | Try the next eligible handpicked reserve. When both reserves have been used or filtered out, leave that place vacant. Keep the completed title as a reference for finding new movies. |
| A chosen whole series enters Continue Watching | Release its shelf place to an editorial reserve once real progress is recorded. The viewer continues from the separate progress row. Starting one episode does not mark the whole series complete. A film merely opened, sampled or paused keeps its place until the normal movie completion signal. |
| The profile already knows most of the curated titles | At the start of a new occurrence, prefer unseen editorial picks. A familiar editorial favorite from an earlier year may return once as a marked rewatch. A title finished in the current occurrence stays off its shelf for the rest of that occurrence, including after a scheduled overlap. Keep hidden titles and series already in Continue Watching off the shelf. Dynamic movies must be unseen by this profile. |
| A relevant film is released during the window | While this occurrence is still open for the profile, admit it on the next successful import once its release and thematic evidence are confirmed. Fill a vacant dynamic place first, otherwise replace the weakest dynamic pick when the premiere qualifies for the recent slot. Keep the remaining order stable. |

Use the profile's existing completion event and manual watched action, including discoveries completed outside the shelf. Precompute a ranked overflow pool before the window opens and extend it as it drains. Prefer at least eight distinct eligible dynamic standbys per theme at preflight. A watch should refill from cached candidates on the next successful Home/history sync, without waiting for the nightly provider job. Apply simultaneous completions as one update. Replayed events are idempotent and do not consume another reserve. Undoing a watched mark restores eligibility on reevaluation while the occurrence is still open for this profile. Avoid replacing the card under the remote's focus until selection or return to Home is safe.

### When fewer than sixteen titles are available

Resolve the calendar winner **before** looking at catalog counts or profile history. For that occurrence alone, refill each group up to eight and deduplicate by title ID. The ten editorial choices, including the two reserves, belong exclusively to that group. Remove empty places from the display. The same rules apply on first opening and after a watch.

| Eligible titles after refill | What the viewer sees |
|---|---|
| 8 editorial + 8 dynamic | The full 16, in one ranked row. |
| 6 editorial + 8 dynamic | The same theme with 14 titles. |
| 8 editorial + 3 dynamic | The same theme with 11 titles. A new qualifying release can grow it back to 12. |
| 0 editorial + 1 dynamic | A one-film row. The original eight still guide discovery. |
| 0 editorial + 0 dynamic, confirmed exhausted | Remove the row for this profile and record that this calendar occurrence is exhausted. Keep it hidden through the remainder of its occurrence. The next theme becomes eligible at its own scheduled time. |

A depleted cache during an interrupted import is an unknown supply. Keep still-valid cards for the current profile and occurrence, remove watched or ineligible ones, and retry discovery for the same theme. If none can currently be shown, hide the row while retrying. Record exhaustion only after a successful check of both pools and their eligible replacements. A confirmed exhaustion survives a restart and later catalog refreshes. Other profiles keep their own lineup.

For example, if a profile finishes the last available Christmas movie on December 24, its seasonal row stays hidden on December 25 and 26. *One Last Movie Before New Year* can appear on December 27. If the profile instead has three Christmas movies left, all three stay on Home.

## Annual programming calendar

All windows use **Europe/Copenhagen**. Date ranges include the last named civil date. Apply explicit clock times where given. The opening eight identify the editorial membership for a fresh profile. Their printed order has no significance on TV. Each pair of reserves is also handpicked and sits outside the initial 16. Films are the default. The two whole-series picks are explicitly identified in their respective lists. Editions and catalog availability must be resolved before publication.

### Winter and spring

#### The Morning After

**When** — January 1, 08:00 to midnight.

**Opening eight** — *School of Rock* · *Paddington 2* · *Legally Blonde* · *The Emperor's New Groove* · *Chef* · *The Princess Bride* · *The Mitchells vs. the Machines* · *Mrs. Harris Goes to Paris*.

**Editorial reserves** — *Sister Act* · *The Muppets (2011)*.

Easy-entry comfort watch. Require a self-contained, warm comedy or family adventure with friendship, purpose or kindness at its center. A recognizable franchise on its own tells us little about tone.

#### Groundhog Day on Repeat

**When** — February 2 only.

**Opening eight** — *Groundhog Day* · *Palm Springs* · *Edge of Tomorrow* · *Happy Death Day* · *Source Code* · *The Map of Tiny Perfect Things* · *Before I Fall* · *Boss Level*.

**Editorial reserves** — *The Endless (2017)* · *The Fare (2018)*.

The protagonist or another central character actually relives a period of time. Borrow the comic reset, puzzle or escalating survival premise from a seed. Ordinary time travel is too broad.

#### Behind the Mask

**When** — Friday 16:00 before Fastelavn through Fastelavn Sunday. Fastelavn is Easter Sunday minus 49 days.

**Opening eight** — *The Mask of Zorro* · *The Incredibles* · *Mulan (1998)* · *Mrs. Doubtfire* · *She's the Man* · *Spider-Man: Into the Spider-Verse* · *A Cinderella Story (2004)* · *Hit Man (Richard Linklater)*.

**Editorial reserves** — *Some Like It Hot* · *Face/Off*.

A mask, disguise, masquerade or double life materially changes relationships or drives the action. A costume in one scene is insufficient.

#### It's Complicated

**When** — February 10–14.

**Opening eight** — *10 Things I Hate About You* · *To All the Boys I've Loved Before* · *When Harry Met Sally...* · *Love & Basketball* · *The Big Sick* · *The Half of It* · *Crazy Rich Asians* · *Red, White & Royal Blue*.

**Editorial reserves** — *Saving Face (2004)* · *Set It Up*.

Love is the central story. Use the references to span first love, misunderstandings, queer romance and adult relationships. A romance subplot in an action picture is too weak.

#### Snowed In

**When** — Winter school break including the surrounding weekends. Editorial fallback is Saturday before ISO week 7 through its Sunday.

**Opening eight** — *The Thing (1982)* · *Fargo (1996)* · *The Holdovers* · *Frozen (2013)* · *Society of the Snow* · *The Hateful Eight* · *Snowpiercer (2013)* · *Arctic (2018)*.

**Editorial reserves** — *Eight Below* · *Force Majeure (2014)*.

Winter conditions, isolation or being stranded must change the choices people make. Allow warm winter company and tense survival films. A snowy establishing shot is weak evidence.

#### The One That Got Away

**When** — Two calendar days before the official Oscar ceremony's host-city date through the day after it, shown on Danish civil dates.

**Opening eight** — *Arrival* · *The Social Network* · *Lady Bird* · *Good Will Hunting* · *Get Out* · *La La Land* · *Past Lives* · *Barbie*.

**Editorial reserves** — *Hidden Figures* · *Dunkirk (2017)*.

Confirm a Best Picture nomination and a different winner for that ceremony. Then compare the filmmaking or story with one of the eight. Admit current-year non-winners only after the result is official. [Academy Awards database](https://awardsdatabase.oscars.org/)

#### Under 90 Minutes

**When** — Friday 16:00 before the spring clock change through the Sunday of the change.

**Opening eight** — *Run Lola Run* · *Before Sunset* · *My Neighbor Totoro* · *Fantastic Mr. Fox* · *Cloverfield* · *Frances Ha* · *Modern Times* · *Rye Lane*.

**Editorial reserves** — *Attack the Block* · *Shaun the Sheep Movie*.

Verify the feature's actual edition runs under 90 minutes. Match the examples' compact storytelling across animation, thrillers, romance and comedy. Exclude runtimes rounded down from 90 and unverified cuts. [Before Sunset](https://www.criterion.com/films/28693-before-sunset) · [My Neighbor Totoro](https://gkids.com/films/my-neighbor-totoro/) · [Rye Lane](https://www.bbfc.co.uk/release/rye-lane-q29sbgvjdglvbjpwwc0xmda1otuz)

#### The Easter Mystery Club

**When** — Palm Sunday through Easter Monday, inclusive.

**Opening eight** — *Clue* · *Knives Out* · *Murder on the Orient Express (1974)* · *Enola Holmes* · *See How They Run* · *The Kid Detective* · *Gosford Park* · *Zootopia*.

**Editorial reserves** — *The Last of Sheila* · *Confess, Fletch*.

A complete mystery with a question, investigation and resolution. Draw from manor-house suspects, family sleuths and modern detective comedies. A crime backdrop alone is insufficient.

#### Trust No One

**When** — April 1 only.

**Opening eight** — *The Truman Show* · *The Game (1997)* · *The Sting* · *The Prestige* · *Matchstick Men* · *The Handmaiden* · *Sharper* · *Now You See Me*.

**Editorial reserves** — *Ocean's Eleven (2001)* · *The Spanish Prisoner*.

Look for a con, manufactured reality, deception or unreliable point of view at the heart of the film. Keep the explanation spoiler-safe.

#### One Wild Planet

**When** — April 20–22, ending on Earth Day.

**Opening eight** — *WALL-E* · *Princess Mononoke* · *The Wild Robot* · *The Biggest Little Farm* · *Nausicaä of the Valley of the Wind* · *March of the Penguins (2005)* · *My Octopus Teacher* · *Flow (2024)*.

**Editorial reserves** — *The Lorax (2012)* · *Winged Migration*.

Look for human impact on an ecosystem, survival within one, or sustained observation of nature. Link a title to a specific seed through ecology, conservation or wildlife. Landscapes alone are insufficient.

#### May the Fourth

**When** — May 3 at 16:00 through May 4.

**Opening eight** — *Star Wars: A New Hope* · *The Empire Strikes Back* · *Return of the Jedi* · *The Phantom Menace* · *Revenge of the Sith* · *The Force Awakens* · *Rogue One: A Star Wars Story* · *Star Wars: The Clone Wars (2008 film)*.

**Editorial reserves** — *The Last Jedi* · *Solo: A Star Wars Story*.

Use canonical Star Wars story films and independently cataloged feature documentaries about making those films or the franchise itself. A documentary can match a specific opening film through its documented subject. General space opera, short featurettes and TV installments are out. The single-franchise exception applies here. [Official films](https://www.starwars.com/films) · [Example of a standalone franchise documentary](https://www.disturbanceintheforce.com/)

#### Twelve Points!

**When** — First official Eurovision semifinal through the day after the Grand Final.

**Opening eight** — *Eurovision Song Contest: The Story of Fire Saga* · *Pitch Perfect* · *Sing (2016)* · *Teen Spirit (2018)* · *Bandslam* · *Joyful Noise* · *Let It Shine* · *Metal Lords*.

**Editorial reserves** — *American Dreamz* · *The Fighting Temptations*.

Require Eurovision, a stage competition, or a musical act actually working towards a competitive performance. A pop soundtrack by itself is insufficient.

### Summer and autumn

#### Pride on Screen

**When** — June 1–30, resuming between shorter events.

**Opening eight** — *But I'm a Cheerleader* · *Bottoms* · *Pride (2014)* · *Moonlight* · *Love, Simon* · *Portrait of a Lady on Fire* · *The Adventures of Priscilla, Queen of the Desert* · *Paris Is Burning*.

**Editorial reserves** — *The Wedding Banquet (2025)* · *The Half of It*.

An LGBTQ+ character, relationship, community or documented history is central to the story. Use an explicit synopsis or credible editorial evidence. Vary intimate romance, comedy and community history.

#### Made in Denmark

**When** — June 4–6, centered on Constitution Day on June 5.

**Opening eight** — *The Olsen Gang (1968)* · *Another Round* · *Borgen (2010, whole series)* · *Terkel in Trouble* · *Flickering Lights* · *Flee (2021)* · *The Promised Land (2023)* · *The Guilty (2018)*.

**Editorial reserves** — *Babette's Feast* · *Riders of Justice*.

Confirm Danish production or a documented Danish-led co-production. Use the opening picks to bridge crime, dry comedy, family cinema and character drama. A Danish actor alone is insufficient. [Borgen](https://drsales.dk/programmes/borgen-government-i-ii-iii/) · [Flee](https://www.norden.org/en/nominerade/flee-denmark)

#### Midsummer After Dark

**When** — June 20–23, peaking on Sankt Hans Eve, June 23.

**Opening eight** — *Midsommar* · *Kiki's Delivery Service* · *The Wicker Man (1973)* · *Wolfwalkers* · *The Craft (1996)* · *Song of the Sea* · *The Witch (2015)* · *Practical Magic*.

**Editorial reserves** — *The Secret of Kells* · *The Ritual (2017)*.

A folk ritual, local legend or witchcraft drives the story. Distinguish a story set at midsummer from a folk-horror or family-folklore neighbor. The Wicker Man qualifies through its May Day ritual.

#### One More Song

**When** — Last Thursday of June through the following Sunday, an editorial festival weekend.

**Opening eight** — *Almost Famous* · *Taylor Swift: The Eras Tour* · *Stop Making Sense* · *The Ballad of Wallis Island* · *This Is Spinal Tap* · *Summer of Soul* · *Sing Street* · *Homecoming: A Film by Beyoncé*.

**Editorial reserves** — *The Last Waltz* · *Bohemian Rhapsody*.

Give concert films, festival documentaries and fictional band stories distinct routes in. Confirm that the performance or the musicians' shared life is central. A hit soundtrack is insufficient.

#### Out of Town

**When** — Summer school break. Fallback is the last Saturday of June through the Sunday six weeks later (43 days after that Saturday).

**Opening eight** — *National Lampoon's Vacation* · *Aftersun* · *Little Miss Sunshine* · *The Mitchells vs. the Machines* · *Girls Trip* · *The Parent Trap (1998)* · *Dirty Dancing* · *Y Tu Mamá También*.

**Editorial reserves** — *The Way Way Back* · *The Darjeeling Limited*.

Travel, a summer stay or a holiday must change the story. Match new picks to road-trip comedy, family holidays or tense trips. Alternate those strands over a long summer window.

#### Fourth of July

**When** — July 2–5 every year, including years when July 4 falls midweek.

**Opening eight** — *Independence Day (1996)* · *Jaws* · *I Know What You Did Last Summer (1997)* · *The Sandlot* · *Flight of the Navigator* · *The Bay (2012)* · *1776 (1972)* · *When Sparks Fly*.

**Editorial reserves** — *Sister of the Bride (2019)* · *Frogs (1972)*.

Require a verified July Fourth setting, celebration, or Independence Day connection that materially affects the story, character or a memorable set piece. Cite the actual scene or biographical connection. An American setting or a July release is insufficient. In *The Sandlot*, fireworks give the kids their one nighttime game. [The Fourth of July scene](https://www.mlb.com/news/top-five-scenes-from-the-sandlot/c-43380712)

#### Back to School

**When** — Three days before the first day back after summer through six days after it. Fallback first day back is the Monday immediately following the fallback summer window.

**Opening eight** — *Mean Girls (2004)* · *Booksmart* · *The Breakfast Club* · *Eighth Grade* · *Clueless* · *Stand and Deliver* · *Roald Dahl's Matilda the Musical* · *The Teachers' Lounge*.

**Editorial reserves** — *Freedom Writers* · *The Class (2008)*.

Require school life or the student/teacher experience to drive the main story. Alternate teen friendships, first-day nerves, classroom comedy and teacher dramas.

#### Halloween After Dark

**When** — October 1–31, with October 30–31 reserved for Halloween.

**Opening eight** — *Hocus Pocus* · *Totally Killer* · *Halloween (1978)* · *Monster House* · *Trick 'r Treat* · *Halloweentown* · *The Nightmare Before Christmas* · *The Curse of Bridge Hollow*.

**Editorial reserves** — *Haunt (2019)* · *Hubie Halloween*.

Give priority to films with a Halloween night, costume tradition or seasonal celebration central to the action. Allow editorially reviewed spooky-season traditions with specific thematic proof. An October release or horror label alone does not qualify.

#### Cabin Weekend

**When** — Autumn school break including the surrounding weekends. Fallback is Saturday before ISO week 42 through its Sunday.

**Opening eight** — *The Great Outdoors* · *Knock at the Cabin* · *The Cabin in the Woods* · *The Kings of Summer* · *On Golden Pond* · *Tucker & Dale vs. Evil* · *Your Sister's Sister* · *The Evil Dead (1981)*.

**Editorial reserves** — *What About Bob?* · *The Lodge (2019)*.

Require a cabin, forest getaway or isolated retreat that shapes the action. Match the relevant family-comedy, group-tension or woodland-survival strand.

#### The Extra Hour

**When** — Friday 16:00 before the autumn clock change through the Sunday of the change.

**Opening eight** — *The Lord of the Rings: The Fellowship of the Ring* · *Dune: Part Two* · *Titanic* · *Wicked (2024)* · *Interstellar* · *Drive My Car* · *The Godfather* · *Avatar: The Way of Water*.

**Editorial reserves** — *Oppenheimer* · *Malcolm X (1992)*.

Verify at least 150 minutes for the available feature edition and match an epic-scale story, ensemble or spectacle. Advertise a named longer cut only when the card can resolve that edition. [Dune: Part Two classification](https://www.classification.gov.au/titles/dune-part-two) · [Drive My Car](https://www.janusfilms.com/films/2040)

### Late autumn, Christmas and pop-ups

#### Dinner Is Served

**When** — Wednesday through Sunday around US Thanksgiving, the fourth Thursday of November.

**Opening eight** — *Planes, Trains and Automobiles* · *Pieces of April* · *Home for the Holidays (1995)* · *Free Birds* · *Thanksgiving (2023)* · *What's Cooking? (2000)* · *The Ice Storm* · *The Humans (2021)*.

**Editorial reserves** — *Krisha (2015)* · *Son in Law*.

Thanksgiving changes the journey, reunion or gathering. A Thanksgiving horror film can qualify through its holiday premise. A restaurant, a turkey or a meal alone is insufficient. [The Humans](https://a24films.com/films/the-humans) · [Krisha](https://a24films.com/films/krisha)

#### Christmas Favorites

**When** — From the earlier of December 1 and the first Sunday of Advent through December 26. The first Sunday of Advent falls between November 27 and December 3.

**Opening eight** — *Home Alone* · *Klaus* · *Elf* · *The Muppet Christmas Carol* · *The Holiday* · *Arthur Christmas* · *The Santa Clause* · *The Julekalender (1991, Danish whole series)*.

**Editorial reserves** — *The Christmas Chronicles* · *The Polar Express*.

Christmas or Advent must drive the story or be a documented household tradition. Balance family reunions, holiday romance, Christmas fantasy and comedy. A December premiere without a holiday premise stays out.

#### Until Sunrise

**When** — 18:00 on the local date of the December solstice to 06:00 the next day.

**Opening eight** — *After Hours* · *Collateral* · *Game Night* · *The Warriors (1979)* · *Adventures in Babysitting (1987)* · *Victoria (2015)* · *Ready or Not (2019)* · *Bodies Bodies Bodies*.

**Editorial reserves** — *Good Time (2017)* · *Date Night*.

The principal action spans one night. Use the overnight pursuit, party, crime or first-date structure of a reference. Night photography alone is insufficient.

#### One Last Movie Before New Year

**When** — December 27–31.

**Opening eight** — *When Harry Met Sally...* · *The Apartment (1960)* · *Ghostbusters II* · *Strange Days (1995)* · *Last Holiday (2006)* · *New Year's Eve (2011)* · *The Poseidon Adventure (1972)* · *Y2K (2024)*.

**Editorial reserves** — *The Hudsucker Proxy* · *200 Cigarettes*.

New Year's Eve, midnight or the turn of the year changes the story or provides its decisive scene. Alternate celebration, romance and countdown thrillers. A general late-December release is too broad. [Y2K](https://a24films.com/films/y2k)

#### Unlucky Friday

**When** — Each Friday the 13th, 00:00 to the next midnight.

**Opening eight** — *Friday the 13th (1980)* · *Final Destination* · *Tucker & Dale vs. Evil* · *Drag Me to Hell* · *The Ring (2002)* · *It Follows* · *Happy Death Day* · *Smile (2022)*.

**Editorial reserves** — *Wish Upon* · *The Grudge (2004)*.

Require a Friday-the-13th story, curse, fatal coincidence or exceptionally bad luck that drives the plot. Vary slashers, supernatural rules and dark comedy. A scary poster alone is too weak.

## Calendar ownership and collisions

Compute Easter and its offsets for each Gregorian year. Use the Copenhagen time-zone database for actual clock-change weekends. Apply the household school calendar by school year when configured, including its first day back. Take the first and last school weekdays of a full break and include the adjacent weekends once. The fallback weeks are product defaults. School dates vary even within a municipality. Store organizer-announced Oscar and Eurovision dates with their year, host zone and source. Cache the astronomical December solstice and convert to local date. Omit an unconfirmed event until its date is known. [Fastelavn](https://www.folkekirken.dk/aarets-hoejtider/fastelavn) · [Danish clock changes](https://www.borger.dk/miljoe-og-energi/Energi/Sommertid) · [Example school calendar](https://www.kk.dk/borger/dagtilbud-og-skole/skole-og-fritid/vejledende-ferieplan-for-skoleaaret-i-koebenhavns-kommune) · [Solstice data](https://aa.usno.navy.mil/data/Earth_Seasons)

Select at most one calendar occurrence from the windows active at the viewer's local time. First protect Easter Sunday and Monday, June 23, July 4, October 30–31 and December 23–26 for their named holiday. Otherwise prefer the shortest active window, then the nearest local event date, then the declaration order in this calendar. The calendar winner is the same for all profiles using that calendar. Catalog depth, watch history and source failures affect only the winner's length or visibility. Leave the slot empty when that winner is exhausted, including while a lower-priority window overlaps. At the next scheduled change, evaluate the newly selected occurrence. For example, *Made in Denmark* takes June 4–6 inside Pride month. A profile that exhausts it on June 4 waits until June 7 for *Pride on Screen* to resume, provided its June occurrence still has titles for that profile. Outside all windows, Home omits the seasonal row. Keep a date-indexed preview of the winners and displaced events for editorial review, including themes that get no screen time that year.

Two real collision tests fall in 2027. Protect Easter Sunday on March 28 over *Under 90 Minutes* and Halloween on October 31 over *The Extra Hour*. *Twelve Points!* runs May 11–16 inclusive, with its final on May 15. The March 14 US Oscar ceremony gives a March 12–15 Danish shelf. [Danish holiday dates](https://www.visitdenmark.dk/danmark/explore/helligdage-i-danmark-gdk1101954) · [Danish clock changes](https://www.borger.dk/miljoe-og-energi/Energi/Sommertid) · [Eurovision organizer](https://www.eurovision.com/newsroom/frequently-asked-questions/faqs-burgas-2027/) · [Oscar broadcaster](https://abc.com/news/555def93-aaa1-481a-b981-61865c6724ba/category/1138628)

## Catalog refresh and implementation

- Extend the companion discovery service that already builds profile Home rows. Reuse its movie/series title cards, ID aliases, poster handling and related-title lookups. Persist editorial reference IDs and traits, former discovery IDs with their original evidence, per-profile viewing signals, current membership, presentation order and overflow candidates separately. Store the exhausted occurrence against the profile and the theme's scheduled occurrence ID, shared across any interrupted subwindows. The following year's occurrence has its own ID. Removing, watching or reordering a card leaves its theme evidence available after restart. A public release anywhere in the world qualifies a title for consideration. The household uses TorBox for playback. A theatrical premiere abroad counts from the confirmed release date. The service also accepts established catalog titles with a verified past release year. Each film still needs a catalog identity and a connection to the theme. [Discovery service](../server/nuvio-discovery/discovery.py) · [Shared index](../server/media-catalog/imdb_index.py) · [TMDB collection queries](../app/src/main/java/com/nuvio/tv/core/tmdb/TmdbCollectionSourceResolver.kt)
- Nightly, refresh active themes and the next 45 days. Discover new movies by date as well as relevant keywords, fetch missing details and resolve IDs. For a verified announcement landing inside a short event, also check for a worldwide release during that event and retry before the final viewing evening. For example, Lucasfilm announced *The Hero Awakens* for May 4, 2027. It directly documents *A New Hope*. It can earn a dynamic May the Fourth place when the worldwide release and film identity are confirmed inside that window. Search a seven-day overlap on incremental updates and an 18-month window weekly for late metadata. Sample worldwide releases and releases carrying the theme keyword. Check films with an announced primary release in the coming year for an earlier international premiere. A future announcement alone cannot qualify a film. Keep page counts bounded and advance cursors after their records are durable. New episodes, new seasons and reissues of existing movies do not receive a fresh-film date. [Movie discovery](https://developer.themoviedb.org/reference/discover-movie) · [Related movies](https://developer.themoviedb.org/reference/movie-recommendations) · [Tracking changes](https://developer.themoviedb.org/docs/tracking-content-changes) · [Lucasfilm announcement](https://www.starwars.com/news/star-wars-the-hero-awakens)
- Process watches separately from provider ingestion. Keep profile identity, theme occurrence, editorial revision, catalog generation and history revision in the publication key. The existing Android session sends only its last 100 distinct watched titles and collapses episodes to their parent. Merge confirmed history with an authoritative profile snapshot or title-level checks for proposed cards. A title missing from a truncated update has unknown status. Persist confirmed watched IDs and consume explicit corrections. For the two series, use a series-in-progress signal from the existing progress state. Maintain the existing authorized profile origin. [Android profile sync](../app/src/main/java/com/nuvio/tv/data/repository/HouseholdRecommendations.kt)
- Atomically publish the current occurrence ID, its validity bounds and the actual list of 0–16 titles. An empty list clears the previous row. Discard a stale calculation when the profile history changes or the calendar selects another occurrence before publication. Use one stable shelf identity under both automatic and manual Home order, with normal title navigation. Re-evaluate validity on Home entry, resume and the next calendar boundary. [Home row ordering](../app/src/main/java/com/nuvio/tv/ui/screens/home/HomeViewModelCatalogUtils.kt) · [Home navigation](../app/src/main/java/com/nuvio/tv/ui/navigation/NuvioNavHost.kt)

## Development sequence

1. **Calendar and editorial data.** Register the 27 theme definitions in this plan with stable occurrence and title identities. Resolve the eight reference titles and two reserves for each theme. Produce a year preview and a clock-driven selector with deterministic overlap rules.
2. **Candidate discovery.** Extend the companion service's existing catalog refresh with related movies, new worldwide releases, evidence of thematic fit and per-theme pools of replacements. Make Christmas the first end-to-end fixture.
3. **Profile selection and refill.** Select up to eight per group, rank the combined set, persist watch effects and refill on the existing history sync. Support short rows, confirmed exhaustion and source recovery across devices.
4. **Android Home and verification.** Integrate the English shelf with normal title details, row ordering, focus, profile switching and time-bound cache expiry. Validate the pilot on TV and run the calendar and selection checks for all 27 themes.

## Acceptance checks

### Worldwide catalog on November 28, 2026

A fresh Thanksgiving test profile received 16 titles from a public-data import, including all eight opening references. *Pieces of April*, *Home for the Holidays*, *Free Birds* and *What’s Cooking?* became eligible from their original releases. Discoveries included *Friendsgiving* and *The House of Yes*. Completing a dynamic film refilled its place and preserved the other fifteen cards. A second test profile retained its original lineup.

### Initial Denmark-only pilot on November 27, 2026

Source revision `8e1907d` passed 77 Discovery tests, 24 media-catalog tests and 34 Android JVM tests. The 2027 calendar preview covered 365 Copenhagen dates and selected all 27 themes during their scheduled windows. The editorial catalog contains 270 selections across the themes, representing 265 distinct IMDb titles.

Two synthetic profiles exercised a December 1 Christmas shelf against a real Danish-market import. The import yielded ten eligible editorial choices and eighteen dynamic candidates. Home selected eight of each and included *Clashing Through the Snow*, a November release with a verified Danish offer. Marking *Elf* watched replaced it with *The Christmas Chronicles*. Marking the dynamic pick *The Santa Clause 2* watched replaced it with *Christmas Eve*. Each action preserved the other fifteen cards and the second profile's lineup. For Thanksgiving, the bounded import had verified five editorial choices and two discoveries, including *Almost Christmas*. Further lookups were pending.

The signed ARM32 Android package matched the household package identity and signing certificate. Physical TV focus, Back navigation and playback remain to be checked during installation.

### Repeatable checks

1. **Pilot Christmas.** With an empty profile, show the eight specified Christmas titles and eight distinct dynamic movies. Give a hypothetical qualifying low-vote Christmas movie a confirmed overseas theatrical release mid-window and verify its admission by the next successful daily import. A December action premiere without a Christmas story must fail even if its cast overlaps with a seed. Build one fixture with a new discovery ranked first and another with an editorial favorite first. Both must contain exactly eight of each type without fixed positions.
2. **Prove refill on the TV.** Watch *Home Alone* and verify that *The Christmas Chronicles* takes its editorial place. Mark a dynamic movie watched on a second device and verify that a different unseen movie takes only its dynamic place. Start *The Julekalender* and verify that the series remains in Continue Watching while a film takes its seasonal place. After *Home Alone* leaves, admit a later movie whose only qualifying reference is *Home Alone*. Complete and remove a dynamic discovery, restart the service, then find a related film through that former discovery. Assert the original editorial link and watch affinity separately, including a negative case where the new film has drifted off theme. Opening details alone must leave all sixteen posters intact. Repeat events, undo a watch, switch profiles and retry with the provider offline.
3. **Test every theme before activation.** Resolve eight reference IDs and two editorial reserves and check unique catalog IDs. Use a fully stocked fixture for the 8 + 8 target and sparse fixtures for returning profiles. Inspect the reason and matched reference for each dynamic pick. Reject episode/season targets, parent shows admitted for one installment, and duplicates across IMDb/TMDB IDs. Exercise exhausted reserves, sparse catalogs, a profile filter, and the movie-only Oscar and runtime shelves. Verify 16 → 15 after a completion with no substitute, 15 → 16 after a matching release, and 1 → 0 after the final watch. Confirm that a genuinely exhausted profile waits through a service restart until the calendar changes. A second profile with titles keeps the same theme.
4. **Preview the calendar.** Test Europe/Copenhagen midnight, both 2027 clock changes, Easter and Halloween collisions, school-date overrides and an unknown organizer date. Exercise the June 4–7 overlap and the December 24–27 example. Confirm that preparing a future pool leaves it invisible before its window, and a provider outage retries the currently scheduled theme. Verify English copy, focus and Back navigation, hidden rows, new-release freshness, expired offline cache and film/whole-series details. Review catalog depth and false positives four weeks before each major window. A verified new movie requires no manual edit to join a running shelf.
