# How Kademlia handles our open problems

7 Nov 2026. HyperDHT, the DHT under Hyperswarm and every Pears app, is a Kademlia DHT (`dht-rpc`). So these lessons apply directly, and most need no new infrastructure. Each claim is marked **[code]** (checked in the installed `hyperdht` 6.36 and `dht-rpc` source), **[spec]** (from a published spec or paper), or **[inferred]** (my reasoning, not yet tested).

## Kademlia in one paragraph

Every node has an ID, and the distance between two IDs is their XOR. Each node keeps "k-buckets": up to k contacts for each distance range, with long-lived contacts preferred. A lookup asks the closest known nodes in parallel for nodes still closer, and reaches the k closest nodes to any key in O(log n) hops. Values and "who has this" records are stored on the k nodes closest to the key, and expire unless republished. HyperDHT asks up to 10 nodes at once (2 in the background) **[code]**, compared with α = 3 in the original paper **[spec]**.

## 1. Millions of listeners on one topic: hot spots

- **The problem [inferred]:** every sharer announces on the same topic, so the ~20 nodes closest to its hash hold every announcement. HyperDHT records expire after 20 minutes, so announcements must be refreshed **[code]**. A lookup returns at most 20 peers per node **[code]**. A million sharers refreshing every ~10 minutes would send roughly 1,600 requests a second to each of those ~20 nodes. Those nodes belong to other people's Pears apps, so this would also be antisocial.
- **What others do [spec]:**
  - Ethereum's discv5 designed topic advertisement around this problem: waiting-time tickets, so a busy topic's ads are rate-limited and spread out. Its spec is still unfinished.
  - Coral's "sloppy hashing" stores a popular key on nodes before the closest ones once those are full, so no node becomes a hot spot.
- **For Brocast:** shard the topic. A listener announces on shard `hash(catalog key ‖ shard)`, where shard = `hash(its key) mod S`, and looks up 2 or 3 shards. The station publishes S in the catalog and grows it with the listener estimate, keeping about 1,000 announcers per shard. While the audience grows slowly, S = 1 and nothing changes. Only Wi-Fi sharers announce at all; everyone else only looks up (already in the profile).

## 2. Counting listeners

- **Kademlia's own estimator [spec]:** with N nodes and uniform IDs, the k-th closest node to a random key is at distance of about k·2^256/N. Averaging a few lookups' k-th distances estimates N, and an attacker would need about N Sybils to move it much. But naive scaling undercounts badly in practice: libtorrent reported about a sixth of the real Mainline size.
- **It doesn't count our listeners directly [inferred]:** phones are ephemeral, so they aren't in routing tables. Density over the whole HyperDHT measures all Pears nodes, not Brocast's.
- **For Brocast:** keep the gossiped sketch as the primary count (§4 of the design note). Add a second, independent check: a sampled census of sharers on a few small DHT topics, each holding about 10 records. A shard can't be counted by looking it up, because a lookup returns at most 20 records **[code]**. Faking census records needs real reachable addresses, so the census bounds how far a hostile peer can inflate the sketch. Details are in design note §12.

## 3. Records that outlive the station (the unplug demo)

- **BEP 44 (Mainline) [spec]:** a mutable item is signed by its owner's key and has a sequence number. "Any node that's interested in keeping a blob in the DHT alive may announce it", by "repeat[ing] the signature … without having the private key". Storage nodes must verify the signature. Items may expire in 2 h, so republish hourly. Puts with a sequence number lower than or equal to the stored one are rejected. Values over 1000 bytes may be refused.
- **HyperDHT [code]:** `mutablePut` accepts a `signMutable` hook, so a peer can re-put the station's stored signature without the secret key. Mutable and immutable values live for 48 h, not 2 h. This answers the open question in design §10.
- **For Brocast:** the station signs a record holding the newest catalog length, its tree hash and the seed keys. Every seeder, and every phone on Wi-Fi, re-puts it about hourly. With the station offline for days, phones still learn the newest edition and who holds it. A forger can't move the sequence number, because they can't sign.

## 4. Churn: phones come and go

- **Kademlia [spec]:** it never evicts a responsive old contact for a new one, because uptime predicts uptime.
- **libp2p [spec]:** only publicly reachable nodes act as DHT servers. Nodes behind NAT or short of resources are clients.
- **HyperDHT [code]:** a node starts ephemeral and becomes persistent only after about 20 minutes of stability behind a friendly NAT (240 ticks of 5 s). Phones on the move therefore stay ephemeral and never weaken the routing tables. This matches design §1.
- **For Brocast [inferred]:** apply the same rule to peers. At the connection limit, keep long-lived peers (seed servers, desktop seeders, phones on Wi-Fi and charging) and drop the newest. With a slowly growing audience, the few long-lived peers matter most.

## 5. Hostile peers

- **S/Kademlia [spec]:** makes node IDs costly to create (crypto puzzles) and looks up over several disjoint paths, so one bad node can't misdirect every query. Even with half the nodes adversarial, 85% of lookups succeed (as reported in secondary sources).
- **HyperDHT IDs [code]:** a node's ID is the BLAKE2b hash of its IP address and port, so it can't pick an ID freely. But one public IP can choose many ports **[inferred]**, so this is weaker than BEP 42, which ties IDs to IP prefixes.
- **For Brocast:** the audio is safe whatever happens, because every block is checked against the station's signed tree. The risks are availability (an eclipsed phone) and an inflated count. Phones always dial the seed servers directly by key, so an eclipse can't cut the station link, and section 2 limits count inflation.

## 6. Duplicate blocks (14 to 58% wire overhead in step 0)

- **Kademlia's trade [spec]:** parallel requests (α) buy latency with extra traffic, and new requests go out only as replies or timeouts arrive. BitTorrent's endgame mode makes the same trade deliberately, only for the last few pieces.
- **For Brocast [inferred]:** with minutes of audio held ahead, racing peers buys nothing. Ask one peer per block, and race only when a block is within about 10 s of its deadline. That needs a Hypercore setting or an upstream change: the third ask for the Pears developers.
- **Also:** HyperDHT's lookup concurrency of 10 is generous for a phone on mobile data. Try a lower value there.

## 7. Locality and version mixing

- **Locality [spec, code]:** XOR closeness says nothing about network closeness (Coral clusters by round-trip time for this reason). HyperDHT already tries local addresses when two peers share a public IP, so phones on the same Wi-Fi connect over the LAN for free **[code]**. Show this on Swarm.
- **Versions [spec, inferred]:** Mainline has carried many clients for about 20 years because its messages tolerate unknown fields. HyperDHT has a `VERSION_MISMATCH` error **[code]**. Lesson for design §11: keep the DHT layer lenient, and put version boundaries in topics and protocol names.

## What this changes in the design

1. Shard topics, with S in the catalog (design note §12). Do this before the audience reaches about 1,000.
2. A DHT pointer record re-put by every seeder and Wi-Fi phone (§10, now confirmed possible in code).
3. A sampled sharer census as a check on the sketch (§12).
4. Keep long-lived peers at the connection limit (§6).
5. Race peers only near the deadline, and lower DHT concurrency on mobile data (step 1 measurements).

## Sources

- Maymounkov and Mazières, "Kademlia" (IPTPS 2002); BEP 44 <https://bittorrent.org/beps/bep_0044.html>
- discv5 topic discovery: <https://github.com/ethereum/devp2p/pull/270>, <https://github.com/ethereum/devp2p/issues/199>
- Coral: <https://www.cs.princeton.edu/~mfreed/docs/coral-nsdi04.pdf>, <https://www.cs.princeton.edu/~mfreed/docs/coral-iptps03.pdf>
- Size estimation: <https://eli.sohl.com/2020/06/05/dht-size-estimation.html>, <https://ik-posts.vercel.app/posts/kad-network-size-mle>, <https://protocollabs.notion.site/Network-Size-Estimation-4692af0841014343ac529c0cdd83d2a9>, <https://docs.rs/mainline/latest/src/mainline/rpc/closest_nodes.rs.html>
- libp2p modes: <https://github.com/libp2p/specs/blob/master/kad-dht/README.md>
- S/Kademlia: <https://telematics.tm.kit.edu/publications/Files/267/SKademlia_slides_2007.pdf>, <https://publikationen.bibliothek.kit.edu/1000087134>
