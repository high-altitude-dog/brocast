# Satellite data (hand-off 44)

`gnss_table.json`: one row per satellite with a signal number today, keyed by PRN (`G12`, `R07`, `E30`, `C21`, `J02`, `I10`).
150 satellites: GPS 32, GLONASS 29, Galileo 32, BeiDou 40, QZSS 7, NavIC 10.

Fields: `prn`, `svn`, `system`, `block` (e.g. `GPS-IIR-M`), `name` (e.g. `NAVSTAR 59`), `launched` (YYYY-MM-DD), `mass_kg` (in orbit),
`plane`, `slot`, `glonass_channel`, `cospar`, `norad`, plus per-system `country_code`, `country`, `operator`.

## Source and terms
- **IGS satellite metadata file** (`igs_satellite_metadata.snx`), maintained by DLR for the International GNSS Service. This copy: release 2441, 22 Oct 2026.
  Format: Steigenberger & Montenbruck, *IGS Satellite Metadata File Description* v1.10, doi:10.57677/metadata-sinex.
- IGS terms: open, free, for scientific, educational **and commercial** use; users must cite and attribute the IGS and its contributing organisations; no warranty.
  Put a credit in About: "Satellite details: International GNSS Service (IGS) satellite metadata, DLR."
- Upstream: `https://files.igs.org/pub/station/general/igs_satellite_metadata.snx`, updated every 2–4 weeks.

## Refresh
`python3 parse_snx.py igs_satellite_metadata.snx gnss_table.json` (current PRN = the row whose Valid_To is open).
Ship the table with the app (48 kB, ~10 kB gzipped). The station fetches the .snx monthly, rebuilds, signs and spreads it over the mesh like the running order.

## Live positions (all satellites, not just those in view)
CelesTrak GP elements, group `gnss` (`https://celestrak.org/NORAD/elements/gp.php?GROUP=gnss&FORMAT=json`), free and public, no account; asks for no excessive automated requests and suggests download once, distribute internally.
The **station** fetches it once or twice a day (~100 kB; negligible egress), signs it and spreads it over the mesh; phones never call CelesTrak. Join to the table on `norad`.
On the phone, SGP4 gives each satellite's position (a few km at MEO; fine for display). That gives: distance and its rate at once (no minute-long wait, no whole-degree steps),
the grey rest-of-fleet in Whole Earth, the below-horizon card ("rises in the NW in 2 h 10 m"), and the orbit line for any satellite.
Keep the current direction + orbit-height estimate as the fallback when elements are older than ~7 days or missing.
