package com.dabomstew.pkrandom.romhandlers;

/*----------------------------------------------------------------------------*/
/*--  Gen3RomHandler.java - randomizer handler for R/S/E/FR/LG.             --*/
/*--                                                                        --*/
/*--  Part of "Universal Pokemon Randomizer" by Dabomstew                   --*/
/*--  Pokemon and any associated names and the like are                     --*/
/*--  trademark and (C) Nintendo 1996-2012.                                 --*/
/*--                                                                        --*/
/*--  The custom code written here is licensed under the terms of the GPL:  --*/
/*--                                                                        --*/
/*--  This program is free software: you can redistribute it and/or modify  --*/
/*--  it under the terms of the GNU General Public License as published by  --*/
/*--  the Free Software Foundation, either version 3 of the License, or     --*/
/*--  (at your option) any later version.                                   --*/
/*--                                                                        --*/
/*--  This program is distributed in the hope that it will be useful,       --*/
/*--  but WITHOUT ANY WARRANTY; without even the implied warranty of        --*/
/*--  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the          --*/
/*--  GNU General Public License for more details.                          --*/
/*--                                                                        --*/
/*--  You should have received a copy of the GNU General Public License     --*/
/*--  along with this program. If not, see <http://www.gnu.org/licenses/>.  --*/
/*----------------------------------------------------------------------------*/

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.dabomstew.pkrandom.FileFunctions;
import com.dabomstew.pkrandom.GFXFunctions;
import com.dabomstew.pkrandom.MiscTweak;
import com.dabomstew.pkrandom.RomFunctions;
import com.dabomstew.pkrandom.Utils;
import com.dabomstew.pkrandom.constants.EmeraldEXConstants;
import com.dabomstew.pkrandom.constants.GlobalConstants;
import com.dabomstew.pkrandom.exceptions.RandomizationException;
import com.dabomstew.pkrandom.exceptions.RandomizerIOException;
import com.dabomstew.pkrandom.pokemon.Encounter;
import com.dabomstew.pkrandom.pokemon.EncounterSet;
import com.dabomstew.pkrandom.pokemon.Evolution;
import com.dabomstew.pkrandom.pokemon.EvolutionDataVersion;
import com.dabomstew.pkrandom.pokemon.EvolutionType;
import com.dabomstew.pkrandom.pokemon.ExpCurve;
import com.dabomstew.pkrandom.pokemon.FieldTM;
import com.dabomstew.pkrandom.pokemon.IngameTrade;
import com.dabomstew.pkrandom.pokemon.ItemList;
import com.dabomstew.pkrandom.pokemon.ItemLocation;
import com.dabomstew.pkrandom.pokemon.Move;
import com.dabomstew.pkrandom.pokemon.MoveLearnt;
import com.dabomstew.pkrandom.pokemon.Pokemon;
import com.dabomstew.pkrandom.pokemon.Trainer;
import com.dabomstew.pkrandom.pokemon.TrainerPokemon;

import compressors.DSDecmp;

public class EmeraldEXRomHandler extends AbstractGBRomHandler {

    public static class Factory extends RomHandler.Factory {

        @Override
        public EmeraldEXRomHandler create(Random random, PrintStream logStream) {
            return new EmeraldEXRomHandler(random, logStream);
        }

        public boolean isLoadable(String filename) {
            long fileLength = new File(filename).length();
            if (fileLength > 32 * 1024 * 1024) {
                return false;
            }
            byte[] loaded = loadFilePartial(filename, 0x100000);
            if (loaded.length == 0) {
                // nope
                return false;
            }
            return detectRomInner(loaded, (int) fileLength);
        }
    }

    public EmeraldEXRomHandler(Random random) {
        super(random, null);
    }

    public EmeraldEXRomHandler(Random random, PrintStream logStream) {
        super(random, logStream);
    }

    private static class RomEntry {
        private String name;
        private String romCode;
        private String tableFile;
        private String hash;
        private int version;
        private boolean copyStaticPokemon;
        private Map<String, Integer> entries = new HashMap<String, Integer>();
        private Map<String, int[]> arrayEntries = new HashMap<String, int[]>();
        private List<StaticPokemon> staticPokemon = new ArrayList<StaticPokemon>();
        private List<TMOrMTTextEntry> tmmtTexts = new ArrayList<TMOrMTTextEntry>();
        private Map<String, String> codeTweaks = new HashMap<String, String>();
        private List<Integer> plotlessItems = new ArrayList<Integer>();

        public RomEntry() {
            this.hash = null;
        }

        public RomEntry(RomEntry toCopy) {
            this.name = toCopy.name;
            this.romCode = toCopy.romCode;
            this.tableFile = toCopy.tableFile;
            this.version = toCopy.version;
            this.copyStaticPokemon = toCopy.copyStaticPokemon;
            this.entries.putAll(toCopy.entries);
            this.arrayEntries.putAll(toCopy.arrayEntries);
            this.staticPokemon.addAll(toCopy.staticPokemon);
            this.tmmtTexts.addAll(toCopy.tmmtTexts);
            this.codeTweaks.putAll(toCopy.codeTweaks);
            this.hash = null;
        }

        private int getValue(String key) {
            if (!entries.containsKey(key)) {
                entries.put(key, 0);
            }
            return entries.get(key);
        }
    }

    private static class TMOrMTTextEntry {
        private int number;
        private int mapBank, mapNumber;
        private int personNum;
        private int offsetInScript;
        private int actualOffset;
        private String template;
        private boolean isMoveTutor;
    }

    private static List<RomEntry> roms;

    static {
        loadROMInfo();
    }

    private static void loadROMInfo() {
        roms = new ArrayList<RomEntry>();
        RomEntry current = null;
        try {
            Scanner sc = new Scanner(FileFunctions.openConfig("emerald_ex_offsets.ini"), "UTF-8");
            while (sc.hasNextLine()) {
                String q = sc.nextLine().trim();
                if (q.contains("//")) {
                    q = q.substring(0, q.indexOf("//")).trim();
                }
                if (!q.isEmpty()) {
                    if (q.startsWith("[") && q.endsWith("]")) {
                        // New rom
                        current = new RomEntry();
                        current.name = q.substring(1, q.length() - 1);
                        roms.add(current);
                    } else {
                        String[] r = q.split("=", 2);
                        if (r.length == 1) {
                            System.err.println("invalid entry " + q);
                            continue;
                        }
                        if (r[1].endsWith("\r\n")) {
                            r[1] = r[1].substring(0, r[1].length() - 2);
                        }
                        r[1] = r[1].trim();
                        // Static Pokemon?
                        if (r[0].equals("StaticPokemon[]")) {
                            if (r[1].startsWith("[") && r[1].endsWith("]")) {
                                String[] offsets = r[1].substring(1, r[1].length() - 1).split(",");
                                int[] offs = new int[offsets.length];
                                int c = 0;
                                for (String off : offsets) {
                                    offs[c++] = parseRIInt(off);
                                }
                                current.staticPokemon.add(new StaticPokemon(offs));
                            } else {
                                int offs = parseRIInt(r[1]);
                                current.staticPokemon.add(new StaticPokemon(offs));
                            }
                        } else if (r[0].equals("TMText[]")) {
                            if (r[1].startsWith("[") && r[1].endsWith("]")) {
                                String[] parts = r[1].substring(1, r[1].length() - 1).split(",", 6);
                                TMOrMTTextEntry tte = new TMOrMTTextEntry();
                                tte.number = parseRIInt(parts[0]);
                                tte.mapBank = parseRIInt(parts[1]);
                                tte.mapNumber = parseRIInt(parts[2]);
                                tte.personNum = parseRIInt(parts[3]);
                                tte.offsetInScript = parseRIInt(parts[4]);
                                tte.template = parts[5];
                                tte.isMoveTutor = false;
                                current.tmmtTexts.add(tte);
                            }
                        } else if (r[0].equals("MoveTutorText[]")) {
                            if (r[1].startsWith("[") && r[1].endsWith("]")) {
                                String[] parts = r[1].substring(1, r[1].length() - 1).split(",", 6);
                                TMOrMTTextEntry tte = new TMOrMTTextEntry();
                                tte.number = parseRIInt(parts[0]);
                                tte.mapBank = parseRIInt(parts[1]);
                                tte.mapNumber = parseRIInt(parts[2]);
                                tte.personNum = parseRIInt(parts[3]);
                                tte.offsetInScript = parseRIInt(parts[4]);
                                tte.template = parts[5];
                                tte.isMoveTutor = true;
                                current.tmmtTexts.add(tte);
                            }
                        } else if (r[0].equals("TMTextSpdc[]")) {
                            if (r[1].startsWith("[") && r[1].endsWith("]")) {
                                String[] parts = r[1].substring(1, r[1].length() - 1).split(",", 3);
                                TMOrMTTextEntry tte = new TMOrMTTextEntry();
                                tte.number = parseRIInt(parts[0]);
                                tte.actualOffset = parseRIInt(parts[1]);
                                tte.template = parts[2];
                                tte.mapBank = -1;
                                tte.mapNumber = -1;
                                tte.isMoveTutor = false;
                                current.tmmtTexts.add(tte);
                            }
                        } else if (r[0].equals("MoveTutorTextSpdc[]")) {
                            if (r[1].startsWith("[") && r[1].endsWith("]")) {
                                String[] parts = r[1].substring(1, r[1].length() - 1).split(",", 3);
                                TMOrMTTextEntry tte = new TMOrMTTextEntry();
                                tte.number = parseRIInt(parts[0]);
                                tte.actualOffset = parseRIInt(parts[1]);
                                tte.template = parts[2];
                                tte.mapBank = -1;
                                tte.mapNumber = -1;
                                tte.isMoveTutor = true;
                                current.tmmtTexts.add(tte);
                            }
                        } else if (r[0].equals("PlotlessKeyItems[]")) {
                            current.plotlessItems.add(parseRIInt(r[1]));
                        } else if (r[0].equals("Game")) {
                            current.romCode = r[1];
                        } else if (r[0].equals("Version")) {
                            current.version = parseRIInt(r[1]);
                        } else if (r[0].equals("MD5Hash")) {
                            current.hash = r[1];
                        } else if (r[0].equals("TableFile")) {
                            current.tableFile = r[1];
                        } else if (r[0].equals("CopyStaticPokemon")) {
                            int csp = parseRIInt(r[1]);
                            current.copyStaticPokemon = (csp > 0);
                        } else if (r[0].endsWith("Tweak")) {
                            current.codeTweaks.put(r[0], r[1]);
                        } else if (r[0].equals("CopyFrom")) {
                            for (RomEntry otherEntry : roms) {
                                if (r[1].equalsIgnoreCase(otherEntry.name)) {
                                    // copy from here
                                    current.arrayEntries.putAll(otherEntry.arrayEntries);
                                    current.entries.putAll(otherEntry.entries);
                                    boolean cTT = (current.getValue("CopyTMText") == 1);
                                    if (current.copyStaticPokemon) {
                                        current.staticPokemon.addAll(otherEntry.staticPokemon);
                                        current.entries.put("StaticPokemonSupport", 1);
                                    } else {
                                        current.entries.put("StaticPokemonSupport", 0);
                                    }
                                    if (cTT) {
                                        current.tmmtTexts.addAll(otherEntry.tmmtTexts);
                                    }
                                    current.tableFile = otherEntry.tableFile;
                                }
                            }
                        } else {
                            if (r[1].startsWith("[") && r[1].endsWith("]")) {
                                String[] offsets = r[1].substring(1, r[1].length() - 1).split(",");
                                if (offsets.length == 1 && offsets[0].trim().isEmpty()) {
                                    current.arrayEntries.put(r[0], new int[0]);
                                } else {
                                    int[] offs = new int[offsets.length];
                                    int c = 0;
                                    for (String off : offsets) {
                                        offs[c++] = parseRIInt(off);
                                    }
                                    current.arrayEntries.put(r[0], offs);
                                }
                            } else {
                                int offs = parseRIInt(r[1]);
                                current.entries.put(r[0], offs);
                            }
                        }
                    }
                }
            }
            sc.close();
        } catch (FileNotFoundException e) {
        }

    }

    private static int parseRIInt(String off) {
        int radix = 10;
        off = off.trim().toLowerCase();
        if (off.startsWith("0x") || off.startsWith("&h")) {
            radix = 16;
            off = off.substring(2);
        }
        try {
            return Integer.parseInt(off, radix);
        } catch (NumberFormatException ex) {
            System.err.println("invalid base " + radix + "number " + off);
            return 0;
        }
    }

    private void loadTextTable(String filename) {
        try {
            Scanner sc = new Scanner(FileFunctions.openConfig(filename + ".tbl"), "UTF-8");
            while (sc.hasNextLine()) {
                String q = sc.nextLine();
                if (!q.trim().isEmpty()) {
                    String[] r = q.split("=", 2);
                    if (r[1].endsWith("\r\n")) {
                        r[1] = r[1].substring(0, r[1].length() - 2);
                    }
                    tb[Integer.parseInt(r[0], 16)] = r[1];
                    d.put(r[1], (byte) Integer.parseInt(r[0], 16));
                }
            }
            sc.close();
        } catch (FileNotFoundException e) {
        }

    }

    // This ROM's data
    private Pokemon[] pokes, pokesInternal;
    private List<Pokemon> pokemonList;
    private int numRealPokemon;
    private Move[] moves;
    private RomEntry romEntry;
    private boolean havePatchedObedience;
    public String[] tb;
    public Map<String, Byte> d;
    private String[] abilityNames;
    private String[] itemNames;
    private boolean mapLoadingDone;
    private List<ItemLocationInner> itemOffs;
    private String[][] mapNames;
    private boolean isRomHack;
    private int[] internalToPokedex, pokedexToInternal;
    private int pokedexCount;
    private String[] pokeNames;
    private ItemList allowedItems, nonBadItems;

    @Override
    public boolean detectRom(byte[] rom) {
        return detectRomInner(rom, rom.length);
    }

    private static boolean detectRomInner(byte[] rom, int romSize) {
        if (romSize != 32 * 1024 * 1024) {
            return false; // size check
        }
        // Map Banks header
        if (find(rom, EmeraldEXConstants.mapBanksPointerPrefix) == -1) {
            System.err.println("No mapbanks");
            return false;
        }
        for (RomEntry re : roms) {
            if (romCode(rom, re.romCode) && (rom[EmeraldEXConstants.romVersionOffset] & 0xFF) == re.version) {
                if (re.hash != null && rom.length == romSize) {
                    try {
                        MessageDigest md = MessageDigest.getInstance("MD5");
                        byte[] digest = md.digest(rom);
                        String hash = Utils.toHexString(digest);
                        return hash.equalsIgnoreCase(re.hash);
                    } catch (NoSuchAlgorithmException e) {
                        return false;
                    }
                }
                return true; // match
            }
        }
        return false; // GBA rom we don't support yet
    }

    @Override
    public void loadedRom() {
        for (RomEntry re : roms) {
            if (romCode(rom, re.romCode) && (rom[0xBC] & 0xFF) == re.version) {
                romEntry = new RomEntry(re); // clone so we can modify
                break;
            }
        }

        tb = new String[256];
        d = new HashMap<String, Byte>();
        isRomHack = false;

        // Pokemon names offset
        romEntry.entries.put("PokemonNames", readPointer(EmeraldEXConstants.efrlgPokemonNamesPointer));
        romEntry.entries.put("MoveNames", readPointer(EmeraldEXConstants.efrlgMoveNamesPointer));
        romEntry.entries.put("AbilityNames", readPointer(EmeraldEXConstants.efrlgAbilityNamesPointer));
        romEntry.entries.put("ItemData", readPointer(EmeraldEXConstants.efrlgItemDataPointer));
        romEntry.entries.put("MoveData", readPointer(EmeraldEXConstants.efrlgMoveDataPointer));
        romEntry.entries.put("PokemonStats", readPointer(EmeraldEXConstants.efrlgPokemonStatsPointer));
        romEntry.entries.put("FrontSprites", readPointer(EmeraldEXConstants.efrlgFrontSpritesPointer));
        romEntry.entries.put("PokemonPalettes", readPointer(EmeraldEXConstants.efrlgPokemonPalettesPointer));
        romEntry.entries.put("MoveTutorCompatibility",
                romEntry.getValue("MoveTutorData") + romEntry.getValue("MoveTutorMoves") * 2);

        loadTextTable(romEntry.tableFile);

        loadPokemonNames();
        loadPokedex();
        loadPokemonStats();
        constructPokemonList();
        populateEvolutions();
        loadMoves();

        // map banks
        int baseMapsOffset = findMultiple(rom, EmeraldEXConstants.mapBanksPointerPrefix).get(0);
        romEntry.entries.put("MapHeaders", readPointer(baseMapsOffset + 12));
        this.determineMapBankSizes();

        // map labels
        int baseMLOffset = find(rom, EmeraldEXConstants.rseMapLabelsPointerPrefix);
        romEntry.entries.put("MapLabels", readPointer(baseMLOffset + 12));

        mapLoadingDone = false;
        loadAbilityNames();
        loadItemNames();

        allowedItems = EmeraldEXConstants.allowedItems.copy();
        nonBadItems = EmeraldEXConstants.nonBadItems.copy();
    }

    @Override
    public void savingRom() {
        savePokemonStats();
        saveMoves();
    }

    private void loadPokedex() {
        int pdOffset = romEntry.getValue("PokedexOrder");
        int numInternalPokes = romEntry.getValue("PokemonCount");
        int maxPokedex = 0;
        internalToPokedex = new int[numInternalPokes + 1];
        pokedexToInternal = new int[numInternalPokes + 1];
        for (int i = 1; i <= numInternalPokes; i++) {
            int dexEntry = readWord(rom, pdOffset + (i - 1) * 2);
            if (dexEntry != 0) {
                internalToPokedex[i] = dexEntry;
                // take the first pokemon only for each dex entry
                if (pokedexToInternal[dexEntry] == 0) {
                    pokedexToInternal[dexEntry] = i;
                }
                maxPokedex = Math.max(maxPokedex, dexEntry);
            }
        }
        this.pokedexCount = maxPokedex;
    }

    private void constructPokemonList() {
        pokemonList = Arrays.asList(pokes);
        numRealPokemon = pokemonList.size() - 1;
    }

    private void loadPokemonStats() {
        pokes = new Pokemon[this.pokedexCount + 1];
        int numInternalPokes = romEntry.getValue("PokemonCount");
        pokesInternal = new Pokemon[numInternalPokes + 1];
        int offs = romEntry.getValue("PokemonStats");
        for (int i = 1; i <= numInternalPokes; i++) {
            Pokemon pk = new Pokemon();
            pk.name = pokeNames[i];
            pk.number = internalToPokedex[i];
            pk.speciesNumber = i;
            if (pk.number != 0 && pokedexToInternal[pk.number] == i) {
                pokes[pk.number] = pk;
            }
            pokesInternal[i] = pk;
            int pkoffs = offs + i * EmeraldEXConstants.baseStatsEntrySize;
            loadBasicPokeStats(pk, pkoffs);
        }
    }

    private void savePokemonStats() {
        // Write pokemon names & stats
        int offs = romEntry.getValue("PokemonNames");
        int nameLen = romEntry.getValue("PokemonNameLength");
        int offs2 = romEntry.getValue("PokemonStats");
        int numInternalPokes = romEntry.getValue("PokemonCount");
        for (int i = 1; i <= numInternalPokes; i++) {
            Pokemon pk = pokesInternal[i];
            int stringOffset = offs + i * nameLen;
            writeFixedLengthString(pk.name, stringOffset, nameLen);
            saveBasicPokeStats(pk, offs2 + i * EmeraldEXConstants.baseStatsEntrySize);
        }
        writeEvolutions();
    }

    private void loadMoves() {
        int moveCount = romEntry.getValue("MoveCount");
        moves = new Move[moveCount + 1];
        int offs = romEntry.getValue("MoveData");
        int nameoffs = romEntry.getValue("MoveNames");
        int namelen = romEntry.getValue("MoveNameLength");
        for (int i = 1; i <= moveCount; i++) {
            moves[i] = new Move();
            moves[i].name = readFixedLengthString(nameoffs + i * namelen, namelen);
            moves[i].number = i;
            moves[i].internalId = i;
            moves[i].effectIndex = this.readWord(offs + i * 20);
            moves[i].hitratio = rom[offs + i * 20 + 5] & 0xFF;
            moves[i].power = this.readWord(offs + i * 20 + 2);
            moves[i].pp = rom[offs + i * 20 + 6] & 0xFF;
            moves[i].type = EmeraldEXConstants.typeTable[rom[offs + i * 20 + 4] & 0xFF];
            moves[i].valid = moves[i].effectIndex != EmeraldEXConstants.placeholderMoveEffectIndex;

            if (GlobalConstants.normalMultihitMoves.contains(i)) {
                moves[i].hitCount = 3;
            } else if (GlobalConstants.doubleHitMoves.contains(i)) {
                moves[i].hitCount = 2;
            } else if (i == GlobalConstants.TRIPLE_KICK_INDEX) {
                moves[i].hitCount = 2.71; // this assumes the first hit lands
            }
        }

    }

    private void saveMoves() {
        int moveCount = romEntry.getValue("MoveCount");
        int offs = romEntry.getValue("MoveData");
        for (int i = 1; i <= moveCount; i++) {
            writeWord(offs + i * 20, moves[i].effectIndex);
            writeWord(offs + i * 20 + 2, moves[i].power);
            rom[offs + i * 20 + 4] = EmeraldEXConstants.typeToByte(moves[i].type);
            int hitratio = (int) Math.round(moves[i].hitratio);
            if (hitratio < 0) {
                hitratio = 0;
            }
            if (hitratio > 100) {
                hitratio = 100;
            }
            rom[offs + i * 20 + 5] = (byte) hitratio;
            rom[offs + i * 20 + 6] = (byte) moves[i].pp;
        }
    }

    public List<Move> getMoves() {
        return Arrays.asList(moves);
    }

    private void loadBasicPokeStats(Pokemon pkmn, int offset) {
        pkmn.hp = rom[offset + EmeraldEXConstants.bsHPOffset] & 0xFF;
        pkmn.attack = rom[offset + EmeraldEXConstants.bsAttackOffset] & 0xFF;
        pkmn.defense = rom[offset + EmeraldEXConstants.bsDefenseOffset] & 0xFF;
        pkmn.speed = rom[offset + EmeraldEXConstants.bsSpeedOffset] & 0xFF;
        pkmn.spatk = rom[offset + EmeraldEXConstants.bsSpAtkOffset] & 0xFF;
        pkmn.spdef = rom[offset + EmeraldEXConstants.bsSpDefOffset] & 0xFF;
        // Type
        pkmn.primaryType = EmeraldEXConstants.typeTable[rom[offset + EmeraldEXConstants.bsPrimaryTypeOffset] & 0xFF];
        pkmn.secondaryType = EmeraldEXConstants.typeTable[rom[offset + EmeraldEXConstants.bsSecondaryTypeOffset]
                & 0xFF];
        // Only one type?
        if (pkmn.secondaryType == pkmn.primaryType) {
            pkmn.secondaryType = null;
        }
        pkmn.catchRate = rom[offset + EmeraldEXConstants.bsCatchRateOffset] & 0xFF;
        pkmn.growthCurve = ExpCurve.fromByte(rom[offset + EmeraldEXConstants.bsGrowthCurveOffset]);
        // Abilities
        pkmn.ability1 = readWord(offset + EmeraldEXConstants.bsAbility1Offset);
        pkmn.ability2 = readWord(offset + EmeraldEXConstants.bsAbility2Offset);
        pkmn.ability3 = readWord(offset + EmeraldEXConstants.bsAbility3Offset);

        // Held Items?
        int item1 = readWord(offset + EmeraldEXConstants.bsCommonHeldItemOffset);
        int item2 = readWord(offset + EmeraldEXConstants.bsRareHeldItemOffset);

        if (item1 == item2) {
            // guaranteed
            pkmn.guaranteedHeldItem = item1;
            pkmn.commonHeldItem = 0;
            pkmn.rareHeldItem = 0;
        } else {
            pkmn.guaranteedHeldItem = 0;
            pkmn.commonHeldItem = item1;
            pkmn.rareHeldItem = item2;
        }
        pkmn.darkGrassHeldItem = -1;

        pkmn.genderRatio = rom[offset + EmeraldEXConstants.bsGenderRatioOffset] & 0xFF;
    }

    private void saveBasicPokeStats(Pokemon pkmn, int offset) {
        rom[offset + EmeraldEXConstants.bsHPOffset] = (byte) pkmn.hp;
        rom[offset + EmeraldEXConstants.bsAttackOffset] = (byte) pkmn.attack;
        rom[offset + EmeraldEXConstants.bsDefenseOffset] = (byte) pkmn.defense;
        rom[offset + EmeraldEXConstants.bsSpeedOffset] = (byte) pkmn.speed;
        rom[offset + EmeraldEXConstants.bsSpAtkOffset] = (byte) pkmn.spatk;
        rom[offset + EmeraldEXConstants.bsSpDefOffset] = (byte) pkmn.spdef;
        rom[offset + EmeraldEXConstants.bsPrimaryTypeOffset] = EmeraldEXConstants.typeToByte(pkmn.primaryType);
        if (pkmn.secondaryType == null) {
            rom[offset + EmeraldEXConstants.bsSecondaryTypeOffset] = rom[offset
                    + EmeraldEXConstants.bsPrimaryTypeOffset];
        } else {
            rom[offset + EmeraldEXConstants.bsSecondaryTypeOffset] = EmeraldEXConstants.typeToByte(pkmn.secondaryType);
        }
        rom[offset + EmeraldEXConstants.bsCatchRateOffset] = (byte) pkmn.catchRate;
        rom[offset + EmeraldEXConstants.bsGrowthCurveOffset] = pkmn.growthCurve.toByte();

        writeWord(offset + EmeraldEXConstants.bsAbility1Offset, pkmn.ability1);
        if (pkmn.ability2 == 0) {
            // required to not break evos with random ability
            writeWord(offset + EmeraldEXConstants.bsAbility2Offset, pkmn.ability1);
        } else {
            writeWord(offset + EmeraldEXConstants.bsAbility2Offset, pkmn.ability2);
        }
        if (pkmn.ability3 == 0) {
            // required to not break evos with random ability
            writeWord(offset + EmeraldEXConstants.bsAbility3Offset, pkmn.ability1);
        } else {
            writeWord(offset + EmeraldEXConstants.bsAbility3Offset, pkmn.ability3);
        }

        // Held items
        if (pkmn.guaranteedHeldItem > 0) {
            writeWord(offset + EmeraldEXConstants.bsCommonHeldItemOffset, pkmn.guaranteedHeldItem);
            writeWord(offset + EmeraldEXConstants.bsRareHeldItemOffset, pkmn.guaranteedHeldItem);
        } else {
            writeWord(offset + EmeraldEXConstants.bsCommonHeldItemOffset, pkmn.commonHeldItem);
            writeWord(offset + EmeraldEXConstants.bsRareHeldItemOffset, pkmn.rareHeldItem);
        }

        rom[offset + EmeraldEXConstants.bsGenderRatioOffset] = (byte) pkmn.genderRatio;
    }

    private void loadPokemonNames() {
        int offs = romEntry.getValue("PokemonNames");
        int nameLen = romEntry.getValue("PokemonNameLength");
        int numInternalPokes = romEntry.getValue("PokemonCount");
        pokeNames = new String[numInternalPokes + 1];
        for (int i = 1; i <= numInternalPokes; i++) {
            pokeNames[i] = readFixedLengthString(offs + i * nameLen, nameLen);
        }
    }

    private String readString(int offset, int maxLength) {
        StringBuilder string = new StringBuilder();
        for (int c = 0; c < maxLength; c++) {
            int currChar = rom[offset + c] & 0xFF;
            if (tb[currChar] != null) {
                string.append(tb[currChar]);
            } else {
                if (currChar == EmeraldEXConstants.textTerminator) {
                    break;
                } else if (currChar == EmeraldEXConstants.textVariable) {
                    int nextChar = rom[offset + c + 1] & 0xFF;
                    string.append("\\v" + String.format("%02X", nextChar));
                    c++;
                } else {
                    string.append("\\x" + String.format("%02X", currChar));
                }
            }
        }
        return string.toString();
    }

    private byte[] translateString(String text) {
        List<Byte> data = new ArrayList<Byte>();
        while (text.length() != 0) {
            int i = Math.max(0, 4 - text.length());
            if (text.charAt(0) == '\\' && text.charAt(1) == 'x') {
                data.add((byte) Integer.parseInt(text.substring(2, 4), 16));
                text = text.substring(4);
            } else if (text.charAt(0) == '\\' && text.charAt(1) == 'v') {
                data.add((byte) EmeraldEXConstants.textVariable);
                data.add((byte) Integer.parseInt(text.substring(2, 4), 16));
                text = text.substring(4);
            } else {
                while (!(d.containsKey(text.substring(0, 4 - i)) || (i == 4))) {
                    i++;
                }
                if (i == 4) {
                    text = text.substring(1);
                } else {
                    data.add(d.get(text.substring(0, 4 - i)));
                    text = text.substring(4 - i);
                }
            }
        }
        byte[] ret = new byte[data.size()];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = data.get(i);
        }
        return ret;
    }

    private String readFixedLengthString(int offset, int length) {
        return readString(offset, length);
    }

    public String readVariableLengthString(int offset) {
        return readString(offset, Integer.MAX_VALUE);
    }

    private void writeFixedLengthString(String str, int offset, int length) {
        byte[] translated = translateString(str);
        int len = Math.min(translated.length, length);
        System.arraycopy(translated, 0, rom, offset, len);
        if (len < length) {
            rom[offset + len] = (byte) EmeraldEXConstants.textTerminator;
            len++;
        }
        while (len < length) {
            rom[offset + len] = 0;
            len++;
        }
    }

    private void writeVariableLengthString(String str, int offset) {
        byte[] translated = translateString(str);
        System.arraycopy(translated, 0, rom, offset, translated.length);
        rom[offset + translated.length] = (byte) 0xFF;
    }

    private int lengthOfStringAt(int offset) {
        int len = 0;
        while ((rom[offset + (len++)] & 0xFF) != 0xFF) {
        }
        return len - 1;
    }

    public byte[] traduire(String str) {
        return translateString(str);
    }

    private static boolean romName(byte[] rom, String name) {
        try {
            int sigOffset = EmeraldEXConstants.romNameOffset;
            byte[] sigBytes = name.getBytes("US-ASCII");
            for (int i = 0; i < sigBytes.length; i++) {
                if (rom[sigOffset + i] != sigBytes[i]) {
                    return false;
                }
            }
            return true;
        } catch (UnsupportedEncodingException ex) {
            return false;
        }

    }

    private static boolean romCode(byte[] rom, String codeToCheck) {
        try {
            int sigOffset = EmeraldEXConstants.romCodeOffset;
            byte[] sigBytes = codeToCheck.getBytes("US-ASCII");
            for (int i = 0; i < sigBytes.length; i++) {
                if (rom[sigOffset + i] != sigBytes[i]) {
                    return false;
                }
            }
            return true;
        } catch (UnsupportedEncodingException ex) {
            return false;
        }

    }

    private int readPointer(int offset) {
        return readLong(offset) - 0x8000000;
    }

    private int readLong(int offset) {
        return (rom[offset] & 0xFF) + ((rom[offset + 1] & 0xFF) << 8) + ((rom[offset + 2] & 0xFF) << 16)
                + (((rom[offset + 3] & 0xFF)) << 24);
    }

    private void writePointer(int offset, int pointer) {
        writeLong(offset, pointer + 0x8000000);
    }

    private void writeLong(int offset, int value) {
        rom[offset] = (byte) (value & 0xFF);
        rom[offset + 1] = (byte) ((value >> 8) & 0xFF);
        rom[offset + 2] = (byte) ((value >> 16) & 0xFF);
        rom[offset + 3] = (byte) (((value >> 24) & 0xFF));
    }

    @Override
    public List<Pokemon> getStarters() {
        List<Pokemon> starters = new ArrayList<Pokemon>();
        int baseOffset = romEntry.getValue("StarterPokemon");
        // do something
        Pokemon starter1 = pokesInternal[readWord(baseOffset)];
        Pokemon starter2 = pokesInternal[readWord(baseOffset + EmeraldEXConstants.rseStarter2Offset)];
        Pokemon starter3 = pokesInternal[readWord(baseOffset + EmeraldEXConstants.rseStarter3Offset)];
        starters.add(starter1);
        starters.add(starter2);
        starters.add(starter3);
        return starters;
    }

    @Override
    public boolean setStarters(List<Pokemon> newStarters) {
        if (newStarters.size() != 3) {
            return false;
        }

        // Support Deoxys/Mew starters in E/FR/LG
        attemptObedienceEvolutionPatches();
        int baseOffset = romEntry.getValue("StarterPokemon");

        int starter0 = newStarters.get(0).speciesNumber;
        int starter1 = newStarters.get(1).speciesNumber;
        int starter2 = newStarters.get(2).speciesNumber;

        // US
        // order: 0, 1, 2
        writeWord(baseOffset, starter0);
        writeWord(baseOffset + EmeraldEXConstants.rseStarter2Offset, starter1);
        writeWord(baseOffset + EmeraldEXConstants.rseStarter3Offset, starter2);
        return true;

    }

    @Override
    public List<Integer> getStarterHeldItems() {
        List<Integer> sHeldItems = new ArrayList<Integer>();
        int baseOffset = romEntry.getValue("StarterItems");
        int i1 = rom[baseOffset] & 0xFF;
        int i2 = rom[baseOffset + 2] & 0xFF;
        if (i2 == 0) {
            sHeldItems.add(i1);
        } else {
            sHeldItems.add(i2 + 0xFF);
        }
        return sHeldItems;
    }

    @Override
    public void setStarterHeldItems(List<Integer> items) {
        if (items.size() != 1) {
            return;
        }
        int item = items.get(0);
        int baseOffset = romEntry.getValue("StarterItems");
        if (item <= 0xFF) {
            rom[baseOffset] = (byte) item;
            rom[baseOffset + 2] = 0;
            rom[baseOffset + 3] = EmeraldEXConstants.gbaAddRxOpcode | EmeraldEXConstants.gbaR2;
        } else {
            rom[baseOffset] = (byte) 0xFF;
            rom[baseOffset + 2] = (byte) (item - 0xFF);
            rom[baseOffset + 3] = EmeraldEXConstants.gbaAddRxOpcode | EmeraldEXConstants.gbaR2;
        }
    }

    @Override
    public List<EncounterSet> getEncounters(boolean useTimeOfDay, boolean condenseSlots) {
        if (!mapLoadingDone) {
            preprocessMaps();
            mapLoadingDone = true;
        }

        String[] todNames = new String[] { "Morning", "Day", "Night" };

        int startOffs = romEntry.getValue("WildPokemon");
        List<EncounterSet> encounterAreas = new ArrayList<EncounterSet>();
        Set<Integer> seenOffsets = new TreeSet<Integer>();
        int offs = startOffs;
        while (true) {
            // Read pointers
            int bank = rom[offs] & 0xFF;
            int map = rom[offs + 1] & 0xFF;
            if (bank == 0xFF && map == 0xFF) {
                break;
            }

            String mapName = mapNames[bank][map];

            int[] typeSlotCounts = { 6, 5, 5, 2 };
            String[] typeNames = { "Grass/Cave", "Surfing", "Rock Smash", "Fishing" };
            for (int type = 0; type < 4; type++) {
                int typePtr = readPointer(offs + 4 + type * 4);
                if (typePtr >= 0 && typePtr < rom.length && rom[typePtr] != 0) {
                    int rate = rom[typePtr] & 0xFF;
                    if (useTimeOfDay) {
                        for (int time = 0; time < 3; time++) {
                            String areaName = String.format("%s %s (%s)", mapName, typeNames[type], todNames[time]);
                            int dataOffset = readPointer(typePtr + 4 + time * 4);
                            if (!seenOffsets.contains(dataOffset)) {
                                if (type == 3) {
                                    encounterAreas
                                            .add(readWildAreaFishing(dataOffset, rate, typeSlotCounts[type], areaName));
                                } else {
                                    encounterAreas.add(readWildArea(dataOffset, rate, typeSlotCounts[type], areaName));
                                }
                                seenOffsets.add(dataOffset);
                            }
                        }
                    } else {
                        // Use Day only
                        String areaName = String.format("%s %s", mapName, typeNames[type]);
                        int dataOffset = readPointer(typePtr + 8);
                        if (!seenOffsets.contains(dataOffset)) {
                            if (type == 3) {
                                encounterAreas
                                        .add(readWildAreaFishing(dataOffset, rate, typeSlotCounts[type], areaName));
                            } else {
                                encounterAreas.add(readWildArea(dataOffset, rate, typeSlotCounts[type], areaName));
                            }
                            seenOffsets.add(dataOffset);
                        }
                    }
                }
            }

            offs += 20;
        }
        if (romEntry.arrayEntries.containsKey("BattleTrappersBanned")) {
            // Some encounter sets aren't allowed to have Pokemon
            // with Arena Trap, Shadow Tag etc.
            int[] bannedAreas = romEntry.arrayEntries.get("BattleTrappersBanned");
            Set<Pokemon> battleTrappers = new HashSet<Pokemon>();
            for (Pokemon pk : getPokemon()) {
                if (hasBattleTrappingAbility(pk)) {
                    battleTrappers.add(pk);
                }
            }
            for (int areaIdx : bannedAreas) {
                encounterAreas.get(areaIdx).bannedPokemon.addAll(battleTrappers);
            }
        }
        return encounterAreas;
    }

    private boolean hasBattleTrappingAbility(Pokemon pokemon) {
        return pokemon != null && (GlobalConstants.battleTrappingAbilities.contains(pokemon.ability1)
                || GlobalConstants.battleTrappingAbilities.contains(pokemon.ability2));
    }

    private EncounterSet readWildArea(int dataOffset, int rate, int numOfEntries, String setName) {
        EncounterSet thisSet = new EncounterSet();
        thisSet.rate = rate;
        thisSet.displayName = setName;
        // Read the entries
        for (int i = 0; i < numOfEntries; i++) {
            // min, max, species, species
            Encounter enc = new Encounter();
            enc.level = rom[dataOffset + i * 4];
            enc.maxLevel = rom[dataOffset + i * 4 + 1];
            try {
                enc.pokemon = pokesInternal[readWord(dataOffset + i * 4 + 2)];
            } catch (ArrayIndexOutOfBoundsException ex) {
                throw ex;
            }
            thisSet.encounters.add(enc);
        }
        return thisSet;
    }

    private EncounterSet readWildAreaFishing(int dataOffset, int rate, int numOfEntries, String setName) {
        EncounterSet thisSet = new EncounterSet();
        thisSet.rate = rate;
        thisSet.displayName = setName;
        // Grab the *real* pointer to data

        // Read the entries
        for (int i = 0; i < numOfEntries; i++) {
            // min, max, species, species
            Encounter enc = new Encounter();
            int realIndex = i;
            enc.level = rom[dataOffset + realIndex * 4];
            enc.maxLevel = rom[dataOffset + realIndex * 4 + 1];
            try {
                enc.pokemon = pokesInternal[readWord(dataOffset + realIndex * 4 + 2)];
            } catch (ArrayIndexOutOfBoundsException ex) {
                throw ex;
            }
            thisSet.encounters.add(enc);
        }
        return thisSet;
    }

    @Override
    public void setEncounters(boolean useTimeOfDay, boolean condenseSlots, List<EncounterSet> encounters) {
        // Support Deoxys/Mew catches in E/FR/LG
        attemptObedienceEvolutionPatches();

        int startOffs = romEntry.getValue("WildPokemon");
        Iterator<EncounterSet> encounterAreas = encounters.iterator();
        Set<Integer> seenOffsets = new TreeSet<Integer>();
        int offs = startOffs;
        while (true) {
            // Read pointers
            int bank = rom[offs] & 0xFF;
            int map = rom[offs + 1] & 0xFF;
            if (bank == 0xFF && map == 0xFF) {
                break;
            }

            int[] typeSlotCounts = { 6, 5, 5, 2 };
            for (int type = 0; type < 4; type++) {
                int typePtr = readPointer(offs + 4 + type * 4);
                if (typePtr >= 0 && typePtr < rom.length && rom[typePtr] != 0) {
                    if (useTimeOfDay) {
                        for (int time = 0; time < 3; time++) {
                            int dataOffset = readPointer(typePtr + 4 + time * 4);
                            if (!seenOffsets.contains(dataOffset)) {
                                EncounterSet area = encounterAreas.next();
                                if (time == 0) {
                                    // write encounter rate from first data set
                                    // for each area/type combo
                                    rom[typePtr] = (byte) area.rate;
                                }
                                if (type == 3) {
                                    writeWildAreaFishing(dataOffset, area);
                                } else {
                                    writeWildArea(dataOffset, typeSlotCounts[type], area, type == 0);
                                }
                                seenOffsets.add(dataOffset);
                            }
                        }
                    } else {
                        // Use Day only and write it over all three
                        int dayDataOffset = readPointer(typePtr + 8);
                        if (!seenOffsets.contains(dayDataOffset)) {
                            EncounterSet area = encounterAreas.next();
                            rom[typePtr] = (byte) area.rate;
                            if (type == 3) {
                                writeWildAreaFishing(readPointer(typePtr + 4), area);
                                writeWildAreaFishing(readPointer(typePtr + 8), area);
                                writeWildAreaFishing(readPointer(typePtr + 12), area);
                            } else {
                                writeWildArea(readPointer(typePtr + 4), typeSlotCounts[type], area, type == 0);
                                writeWildArea(readPointer(typePtr + 8), typeSlotCounts[type], area, type == 0);
                                writeWildArea(readPointer(typePtr + 12), typeSlotCounts[type], area, type == 0);
                            }
                            seenOffsets.add(dayDataOffset);
                        }
                    }
                }
            }

            offs += 20;
        }
    }

    private void writeWildArea(int dataOffset, int numOfEntries, EncounterSet encounters, boolean duplicateSlots) {
        // Write the entries
        for (int i = 0; i < numOfEntries; i++) {
            Encounter enc = encounters.encounters.get(i);
            // min, max, species, species
            writeWord(dataOffset + i * 4 + 2, enc.pokemon.speciesNumber);
            if (duplicateSlots) {
                writeWord(dataOffset + (i + numOfEntries) * 4 + 2, enc.pokemon.speciesNumber);
            }
        }
    }

    private void writeWildAreaFishing(int dataOffset, EncounterSet encounters) {
        int numOfEntries = 2;
        // Write the entries
        for (int i = 0; i < numOfEntries; i++) {
            Encounter enc = encounters.encounters.get(i);
            // min, max, species, species
            writeWord(dataOffset + i * 4 + 2, enc.pokemon.speciesNumber);
            // Speedchoice duplication.. 4 extra times
            writeWord(dataOffset + (i + numOfEntries) * 4 + 2, enc.pokemon.speciesNumber);
            writeWord(dataOffset + (i + numOfEntries * 2) * 4 + 2, enc.pokemon.speciesNumber);
            writeWord(dataOffset + (i + numOfEntries * 3) * 4 + 2, enc.pokemon.speciesNumber);
            writeWord(dataOffset + (i + numOfEntries * 4) * 4 + 2, enc.pokemon.speciesNumber);
        }
    }

    @Override
    public List<Pokemon> bannedForWildEncounters() {
        return Collections.emptyList();
    }

    @Override
    public boolean hasTimeBasedEncounters() {
        return true;
    }

    @Override
    public List<Trainer> getTrainers() {
        int baseOffset = romEntry.getValue("TrainerData");
        int amount = romEntry.getValue("TrainerCount");
        int entryLen = romEntry.getValue("TrainerEntrySize");
        List<Trainer> theTrainers = new ArrayList<Trainer>();
        List<String> tcnames = this.getTrainerClassNames();
        for (int i = 1; i < amount; i++) {
            int trOffset = baseOffset + i * entryLen;
            Trainer tr = new Trainer();
            tr.offset = trOffset;
            int trainerclass = rom[trOffset + 1] & 0xFF;
            tr.trainerclass = (rom[trOffset + 2] & 0x80) > 0 ? 1 : 0;

            int pokeDataType = rom[trOffset] & 0xFF;
            int numPokes = rom[trOffset + (entryLen - 8)] & 0xFF;
            int pointerToPokes = readPointer(trOffset + (entryLen - 4));
            tr.poketype = pokeDataType;
            tr.name = this.readVariableLengthString(trOffset + 4);
            tr.fullDisplayName = tcnames.get(trainerclass) + " " + tr.name;
            tr.doubleBattle = rom[trOffset + (entryLen - 0x10)] != 0;
            // Pokemon data!
            if (pokeDataType == 0) {
                // blocks of 8 bytes
                for (int poke = 0; poke < numPokes; poke++) {
                    TrainerPokemon thisPoke = new TrainerPokemon();
                    thisPoke.AILevel = readWord(pointerToPokes + poke * 8);
                    thisPoke.level = readWord(pointerToPokes + poke * 8 + 2);
                    thisPoke.pokemon = pokesInternal[readWord(pointerToPokes + poke * 8 + 4)];
                    tr.pokemon.add(thisPoke);
                }
            } else if (pokeDataType == 2) {
                // blocks of 8 bytes
                for (int poke = 0; poke < numPokes; poke++) {
                    TrainerPokemon thisPoke = new TrainerPokemon();
                    thisPoke.AILevel = readWord(pointerToPokes + poke * 8);
                    thisPoke.level = readWord(pointerToPokes + poke * 8 + 2);
                    thisPoke.pokemon = pokesInternal[readWord(pointerToPokes + poke * 8 + 4)];
                    thisPoke.heldItem = readWord(pointerToPokes + poke * 8 + 6);
                    tr.pokemon.add(thisPoke);
                }
            } else if (pokeDataType == 1) {
                // blocks of 16 bytes
                for (int poke = 0; poke < numPokes; poke++) {
                    TrainerPokemon thisPoke = new TrainerPokemon();
                    thisPoke.AILevel = readWord(pointerToPokes + poke * 16);
                    thisPoke.level = readWord(pointerToPokes + poke * 16 + 2);
                    thisPoke.pokemon = pokesInternal[readWord(pointerToPokes + poke * 16 + 4)];
                    thisPoke.move1 = readWord(pointerToPokes + poke * 16 + 6);
                    thisPoke.move2 = readWord(pointerToPokes + poke * 16 + 8);
                    thisPoke.move3 = readWord(pointerToPokes + poke * 16 + 10);
                    thisPoke.move4 = readWord(pointerToPokes + poke * 16 + 12);
                    tr.pokemon.add(thisPoke);
                }
            } else if (pokeDataType == 3) {
                // blocks of 16 bytes
                for (int poke = 0; poke < numPokes; poke++) {
                    TrainerPokemon thisPoke = new TrainerPokemon();
                    thisPoke.AILevel = readWord(pointerToPokes + poke * 16);
                    thisPoke.level = readWord(pointerToPokes + poke * 16 + 2);
                    thisPoke.pokemon = pokesInternal[readWord(pointerToPokes + poke * 16 + 4)];
                    thisPoke.heldItem = readWord(pointerToPokes + poke * 16 + 6);
                    thisPoke.move1 = readWord(pointerToPokes + poke * 16 + 8);
                    thisPoke.move2 = readWord(pointerToPokes + poke * 16 + 10);
                    thisPoke.move3 = readWord(pointerToPokes + poke * 16 + 12);
                    thisPoke.move4 = readWord(pointerToPokes + poke * 16 + 14);
                    tr.pokemon.add(thisPoke);
                }
            }
            theTrainers.add(tr);
        }
        EmeraldEXConstants.trainerTagsE(theTrainers);
        return theTrainers;
    }

    @Override
    public void setTrainers(List<Trainer> trainerData) {
        int baseOffset = romEntry.getValue("TrainerData");
        int amount = romEntry.getValue("TrainerCount");
        int entryLen = romEntry.getValue("TrainerEntrySize");
        Iterator<Trainer> theTrainers = trainerData.iterator();
        int fso = romEntry.getValue("FreeSpace");

        // Get current movesets in case we need to reset them for certain
        // trainer mons.
        Map<Pokemon, List<MoveLearnt>> movesets = this.getMovesLearnt();

        for (int i = 1; i < amount; i++) {
            int trOffset = baseOffset + i * entryLen;
            Trainer tr = theTrainers.next();
            // Do we need to repoint this trainer's data?
            int oldPokeType = rom[trOffset] & 0xFF;
            int oldPokeCount = rom[trOffset + (entryLen - 8)] & 0xFF;
            int newPokeCount = tr.pokemon.size();
            int newDataSize = newPokeCount * ((tr.poketype & 1) == 1 ? 16 : 8);
            int oldDataSize = oldPokeCount * ((oldPokeType & 1) == 1 ? 16 : 8);

            // write out new data first...
            rom[trOffset] = (byte) tr.poketype;
            rom[trOffset + (entryLen - 8)] = (byte) newPokeCount;

            // now, do we need to repoint?
            int pointerToPokes;
            if (newDataSize > oldDataSize) {
                int writeSpace = RomFunctions.freeSpaceFinder(rom, EmeraldEXConstants.freeSpaceByte, newDataSize, fso,
                        true);
                if (writeSpace < fso) {
                    throw new RandomizerIOException("ROM is full");
                }
                writePointer(trOffset + (entryLen - 4), writeSpace);
                pointerToPokes = writeSpace;
            } else {
                pointerToPokes = readPointer(trOffset + (entryLen - 4));
            }

            Iterator<TrainerPokemon> pokes = tr.pokemon.iterator();

            // Write out Pokemon data!
            if ((tr.poketype & 1) == 1) {
                // custom moves, blocks of 16 bytes
                for (int poke = 0; poke < newPokeCount; poke++) {
                    TrainerPokemon tp = pokes.next();
                    writeWord(pointerToPokes + poke * 16, tp.AILevel);
                    writeWord(pointerToPokes + poke * 16 + 2, tp.level);
                    writeWord(pointerToPokes + poke * 16 + 4, tp.pokemon.speciesNumber);
                    int movesStart;
                    if ((tr.poketype & 2) == 2) {
                        writeWord(pointerToPokes + poke * 16 + 6, tp.heldItem);
                        movesStart = 8;
                    } else {
                        movesStart = 6;
                        writeWord(pointerToPokes + poke * 16 + 14, 0);
                    }
                    if (tp.resetMoves) {
                        int[] pokeMoves = RomFunctions.getMovesAtLevel(tp.pokemon, movesets, tp.level);
                        for (int m = 0; m < 4; m++) {
                            writeWord(pointerToPokes + poke * 16 + movesStart + m * 2, pokeMoves[m]);
                        }
                    } else {
                        writeWord(pointerToPokes + poke * 16 + movesStart, tp.move1);
                        writeWord(pointerToPokes + poke * 16 + movesStart + 2, tp.move2);
                        writeWord(pointerToPokes + poke * 16 + movesStart + 4, tp.move3);
                        writeWord(pointerToPokes + poke * 16 + movesStart + 6, tp.move4);
                    }
                }
            } else {
                // no moves, blocks of 8 bytes
                for (int poke = 0; poke < newPokeCount; poke++) {
                    TrainerPokemon tp = pokes.next();
                    writeWord(pointerToPokes + poke * 8, tp.AILevel);
                    writeWord(pointerToPokes + poke * 8 + 2, tp.level);
                    writeWord(pointerToPokes + poke * 8 + 4, tp.pokemon.speciesNumber);
                    if ((tr.poketype & 2) == 2) {
                        writeWord(pointerToPokes + poke * 8 + 6, tp.heldItem);
                    } else {
                        writeWord(pointerToPokes + poke * 8 + 6, 0);
                    }
                }
            }
        }

    }

    @Override
    public List<Pokemon> getPokemon() {
        return pokemonList;
    }

    @Override
    public Map<Pokemon, List<MoveLearnt>> getMovesLearnt() {
        Map<Pokemon, List<MoveLearnt>> movesets = new TreeMap<Pokemon, List<MoveLearnt>>();
        int baseOffset = romEntry.getValue("PokemonMovesets");
        for (int i = 1; i <= numRealPokemon; i++) {
            Pokemon pkmn = pokemonList.get(i);
            int offsToPtr = baseOffset + (pkmn.speciesNumber) * 4;
            int moveDataLoc = readPointer(offsToPtr);
            List<MoveLearnt> moves = new ArrayList<MoveLearnt>();
            int move = readWord(moveDataLoc);
            while (move != 0xFFFF) {
                MoveLearnt ml = new MoveLearnt();
                ml.level = readWord(moveDataLoc + 2);
                ml.move = move;
                moves.add(ml);
                moveDataLoc += 4;
                move = readWord(moveDataLoc);
            }
            movesets.put(pkmn, moves);
        }
        return movesets;
    }

    @Override
    public void setMovesLearnt(Map<Pokemon, List<MoveLearnt>> movesets) {
        int baseOffset = romEntry.getValue("PokemonMovesets");
        int fso = romEntry.getValue("FreeSpace");
        for (int i = 1; i <= numRealPokemon; i++) {
            Pokemon pkmn = pokemonList.get(i);
            int offsToPtr = baseOffset + (pkmn.speciesNumber) * 4;
            int moveDataLoc = readPointer(offsToPtr);
            List<MoveLearnt> moves = movesets.get(pkmn);
            int newMoveCount = moves.size();
            int mloc = moveDataLoc;
            while (readWord(mloc) != 0xFFFF) {
                mloc += 4;
            }
            int currentMoveCount = (mloc - moveDataLoc) / 4;

            if (newMoveCount > currentMoveCount) {
                // Repoint for more space
                int newBytesNeeded = (newMoveCount + 1) * 4;
                int writeSpace = RomFunctions.freeSpaceFinder(rom, EmeraldEXConstants.freeSpaceByte, newBytesNeeded,
                        fso);
                if (writeSpace < fso) {
                    throw new RandomizerIOException("ROM is full");
                }
                writePointer(offsToPtr, writeSpace);
                moveDataLoc = writeSpace;
            }

            // Write new moveset now that space is ensured.
            for (int mv = 0; mv < newMoveCount; mv++) {
                MoveLearnt ml = moves.get(mv);
                writeWord(moveDataLoc, ml.move);
                writeWord(moveDataLoc + 2, ml.level);
                moveDataLoc += 4;
            }

            // If move count changed, new terminator is required
            // In the repoint enough space was reserved to add some padding to
            // make sure the terminator isn't detected as free space.
            // If no repoint, the padding goes over the old moves/terminator.
            if (newMoveCount != currentMoveCount) {
                writeWord(moveDataLoc, 0xFFFF);
                writeWord(moveDataLoc + 2, 0x0000);
            }

        }

    }

    private static class StaticPokemon {
        private int[] offsets;

        public StaticPokemon(int... offsets) {
            this.offsets = offsets;
        }

        public Pokemon getPokemon(EmeraldEXRomHandler parent) {
            return parent.pokesInternal[parent.readWord(offsets[0])];
        }

        public void setPokemon(EmeraldEXRomHandler parent, Pokemon pkmn) {
            int value = pkmn.speciesNumber;
            for (int offset : offsets) {
                parent.writeWord(offset, value);
            }
        }
    }

    @Override
    public List<Pokemon> getStaticPokemon() {
        List<Pokemon> statics = new ArrayList<Pokemon>();
        List<StaticPokemon> staticsHere = romEntry.staticPokemon;
        for (StaticPokemon staticPK : staticsHere) {
            statics.add(staticPK.getPokemon(this));
        }
        return statics;
    }

    @Override
    public boolean setStaticPokemon(List<Pokemon> staticPokemon) {
        // Support Deoxys/Mew gifts/catches in E/FR/LG
        attemptObedienceEvolutionPatches();

        List<StaticPokemon> staticsHere = romEntry.staticPokemon;
        if (staticPokemon.size() != staticsHere.size()) {
            return false;
        }

        for (int i = 0; i < staticsHere.size(); i++) {
            staticsHere.get(i).setPokemon(this, staticPokemon.get(i));
        }
        return true;
    }

    @Override
    public List<Integer> getTMMoves() {
        List<Integer> tms = new ArrayList<Integer>();
        int offset = romEntry.getValue("TmMoves");
        for (int i = 1; i <= EmeraldEXConstants.tmCount; i++) {
            tms.add(readWord(offset + (i - 1) * 2));
        }
        return tms;
    }

    @Override
    public List<Integer> getHMMoves() {
        return EmeraldEXConstants.hmMoves;
    }

    @Override
    public void setTMMoves(List<Integer> moveIndexes) {
        if (!mapLoadingDone) {
            preprocessMaps();
            mapLoadingDone = true;
        }
        int offset = romEntry.getValue("TmMoves");
        for (int i = 1; i <= EmeraldEXConstants.tmCount; i++) {
            writeWord(offset + (i - 1) * 2, moveIndexes.get(i - 1));
        }
        int otherOffset = romEntry.getValue("TmMovesDuplicate");
        if (otherOffset > 0) {
            // Emerald/FR/LG have *two* TM tables
            System.arraycopy(rom, offset, rom, otherOffset, EmeraldEXConstants.tmCount * 2);
        }

        int iiOffset = romEntry.getValue("ItemImages");
        if (iiOffset > 0) {
            int[] pals = romEntry.arrayEntries.get("TmPals");
            // Update the item image palettes
            // Gen3 TMs are 289-338
            for (int i = 0; i < 50; i++) {
                Move mv = moves[moveIndexes.get(i)];
                int typeID = EmeraldEXConstants.typeToByte(mv.type);
                writePointer(iiOffset + (EmeraldEXConstants.tmItemOffset + i) * 8 + 4, pals[typeID]);
            }
        }

        int fsOffset = romEntry.getValue("FreeSpace");

        // Item descriptions
        if (romEntry.getValue("MoveDescriptions") > 0) {
            // JP blocked for now - uses different item structure anyway
            int idOffset = romEntry.getValue("ItemData");
            int mdOffset = romEntry.getValue("MoveDescriptions");
            int entrySize = romEntry.getValue("ItemEntrySize");
            int limitPerLine = EmeraldEXConstants.rseItemDescCharsPerLine;
            for (int i = 0; i < EmeraldEXConstants.tmCount; i++) {
                int itemBaseOffset = idOffset + (i + EmeraldEXConstants.tmItemOffset) * entrySize;
                int moveBaseOffset = mdOffset + (moveIndexes.get(i) - 1) * 4;
                int moveTextPointer = readPointer(moveBaseOffset);
                String moveDesc = readVariableLengthString(moveTextPointer);
                String newItemDesc = RomFunctions.rewriteDescriptionForNewLineSize(moveDesc, "\\n", limitPerLine, ssd);
                // Find freespace
                int fsBytesNeeded = translateString(newItemDesc).length + 1;
                int newItemDescOffset = RomFunctions.freeSpaceFinder(rom, EmeraldEXConstants.freeSpaceByte,
                        fsBytesNeeded, fsOffset);
                if (newItemDescOffset < fsOffset) {
                    String nl = System.getProperty("line.separator");
                    log("Couldn't insert new item description." + nl);
                    return;
                }
                writeVariableLengthString(newItemDesc, newItemDescOffset);
                writePointer(itemBaseOffset + EmeraldEXConstants.itemDataDescriptionOffset, newItemDescOffset);
            }
        }

        // TM Text?
        for (TMOrMTTextEntry tte : romEntry.tmmtTexts) {
            if (tte.actualOffset > 0 && !tte.isMoveTutor) {
                // create the new TM text
                int oldPointer = readPointer(tte.actualOffset);
                if (oldPointer < 0 || oldPointer >= rom.length) {
                    String nl = System.getProperty("line.separator");
                    log("Couldn't insert new TM text. Skipping remaining TM text updates." + nl);
                    return;
                }
                String moveName = this.moves[moveIndexes.get(tte.number - 1)].name;
                // temporarily use underscores to stop the move name being split
                String tmpMoveName = moveName.replace(' ', '_');
                String unformatted = tte.template.replace("[move]", tmpMoveName);
                String newText = RomFunctions.formatTextWithReplacements(unformatted, null, "\\n", "\\l", "\\p",
                        EmeraldEXConstants.regularTextboxCharsPerLine, ssd);
                // get rid of the underscores
                newText = newText.replace(tmpMoveName, moveName);
                // insert the new text into free space
                int fsBytesNeeded = translateString(newText).length + 1;
                int newOffset = RomFunctions.freeSpaceFinder(rom, (byte) 0xFF, fsBytesNeeded, fsOffset);
                if (newOffset < fsOffset) {
                    String nl = System.getProperty("line.separator");
                    log("Couldn't insert new TM text." + nl);
                    return;
                }
                writeVariableLengthString(newText, newOffset);
                // search for copies of the pointer:
                // make a needle of the pointer
                byte[] searchNeedle = new byte[4];
                System.arraycopy(rom, tte.actualOffset, searchNeedle, 0, 4);
                // find copies within 500 bytes either way of actualOffset
                int minOffset = Math.max(0, tte.actualOffset - EmeraldEXConstants.pointerSearchRadius);
                int maxOffset = Math.min(rom.length, tte.actualOffset + EmeraldEXConstants.pointerSearchRadius);
                List<Integer> pointerLocs = RomFunctions.search(rom, minOffset, maxOffset, searchNeedle);
                for (int pointerLoc : pointerLocs) {
                    // write the new pointer
                    writePointer(pointerLoc, newOffset);
                }
            }
        }
    }

    private RomFunctions.StringSizeDeterminer ssd = new RomFunctions.StringSizeDeterminer() {

        @Override
        public int lengthFor(String encodedText) {
            return translateString(encodedText).length;
        }
    };

    @Override
    public int getTMCount() {
        return EmeraldEXConstants.tmCount;
    }

    @Override
    public int getHMCount() {
        return EmeraldEXConstants.hmCount;
    }

    @Override
    public Map<Pokemon, boolean[]> getTMHMCompatibility() {
        Map<Pokemon, boolean[]> compat = new TreeMap<Pokemon, boolean[]>();
        int offset = romEntry.getValue("PokemonTMHMCompat");
        for (int i = 1; i <= numRealPokemon; i++) {
            Pokemon pkmn = pokemonList.get(i);
            int compatOffset = offset + (pkmn.speciesNumber) * 8;
            boolean[] flags = new boolean[EmeraldEXConstants.tmCount + EmeraldEXConstants.hmCount + 1];
            for (int j = 0; j < 8; j++) {
                readByteIntoFlags(flags, j * 8 + 1, compatOffset + j);
            }
            compat.put(pkmn, flags);
        }
        return compat;
    }

    @Override
    public void setTMHMCompatibility(Map<Pokemon, boolean[]> compatData) {
        int offset = romEntry.getValue("PokemonTMHMCompat");
        for (Map.Entry<Pokemon, boolean[]> compatEntry : compatData.entrySet()) {
            Pokemon pkmn = compatEntry.getKey();
            boolean[] flags = compatEntry.getValue();
            int compatOffset = offset + (pkmn.speciesNumber) * 8;
            for (int j = 0; j < 8; j++) {
                rom[compatOffset + j] = getByteFromFlags(flags, j * 8 + 1);
            }
        }
    }

    @Override
    public boolean hasMoveTutors() {
        return true;
    }

    @Override
    public List<Integer> getMoveTutorMoves() {
        if (!hasMoveTutors()) {
            return new ArrayList<Integer>();
        }
        List<Integer> mts = new ArrayList<Integer>();
        int moveCount = romEntry.getValue("MoveTutorMoves");
        int offset = romEntry.getValue("MoveTutorData");
        for (int i = 0; i < moveCount; i++) {
            mts.add(readWord(offset + i * 2));
        }
        return mts;
    }

    @Override
    public void setMoveTutorMoves(List<Integer> moves) {
        if (!hasMoveTutors()) {
            return;
        }
        int moveCount = romEntry.getValue("MoveTutorMoves");
        int offset = romEntry.getValue("MoveTutorData");
        if (moveCount != moves.size()) {
            return;
        }
        for (int i = 0; i < moveCount; i++) {
            writeWord(offset + i * 2, moves.get(i));
        }
        int fsOffset = romEntry.getValue("FreeSpace");

        // Move Tutor Text?
        for (TMOrMTTextEntry tte : romEntry.tmmtTexts) {
            if (tte.actualOffset > 0 && tte.isMoveTutor) {
                // create the new MT text
                int oldPointer = readPointer(tte.actualOffset);
                if (oldPointer < 0 || oldPointer >= rom.length) {
                    throw new RandomizationException(String.format(
                            "Move Tutor Text update failed: couldn't read a move tutor text pointer.\n"
                                    + "Tutor no: %d\n" + "Pointer: 0x%X\n" + "Old pointer: 0x%X",
                            tte.number, tte.actualOffset, oldPointer));
                }
                String moveName = this.moves[moves.get(tte.number)].name;
                // temporarily use underscores to stop the move name being split
                String tmpMoveName = moveName.replace(' ', '_');
                String unformatted = tte.template.replace("[move]", tmpMoveName);
                String newText = RomFunctions.formatTextWithReplacements(unformatted, null, "\\n", "\\l", "\\p",
                        EmeraldEXConstants.regularTextboxCharsPerLine, ssd);
                // get rid of the underscores
                newText = newText.replace(tmpMoveName, moveName);
                // insert the new text into free space
                int fsBytesNeeded = translateString(newText).length + 1;
                int newOffset = RomFunctions.freeSpaceFinder(rom, EmeraldEXConstants.freeSpaceByte, fsBytesNeeded,
                        fsOffset);
                if (newOffset < fsOffset) {
                    String nl = System.getProperty("line.separator");
                    log("Couldn't insert new Move Tutor text." + nl);
                    return;
                }
                writeVariableLengthString(newText, newOffset);
                // search for copies of the pointer:
                // make a needle of the pointer
                byte[] searchNeedle = new byte[4];
                System.arraycopy(rom, tte.actualOffset, searchNeedle, 0, 4);
                // find copies within 500 bytes either way of actualOffset
                int minOffset = Math.max(0, tte.actualOffset - EmeraldEXConstants.pointerSearchRadius);
                int maxOffset = Math.min(rom.length, tte.actualOffset + EmeraldEXConstants.pointerSearchRadius);
                List<Integer> pointerLocs = RomFunctions.search(rom, minOffset, maxOffset, searchNeedle);
                for (int pointerLoc : pointerLocs) {
                    // write the new pointer
                    writePointer(pointerLoc, newOffset);
                }
            }
        }
    }

    @Override
    public Map<Pokemon, boolean[]> getMoveTutorCompatibility() {
        if (!hasMoveTutors()) {
            return new TreeMap<Pokemon, boolean[]>();
        }
        Map<Pokemon, boolean[]> compat = new TreeMap<Pokemon, boolean[]>();
        int moveCount = romEntry.getValue("MoveTutorMoves");
        int offset = romEntry.getValue("MoveTutorCompatibility");
        int bytesRequired = ((moveCount + 7) & ~7) / 8;
        for (int i = 1; i <= numRealPokemon; i++) {
            Pokemon pkmn = pokemonList.get(i);
            int compatOffset = offset + pkmn.speciesNumber * bytesRequired;
            boolean[] flags = new boolean[moveCount + 1];
            for (int j = 0; j < bytesRequired; j++) {
                readByteIntoFlags(flags, j * 8 + 1, compatOffset + j);
            }
            compat.put(pkmn, flags);
        }
        return compat;
    }

    @Override
    public void setMoveTutorCompatibility(Map<Pokemon, boolean[]> compatData) {
        if (!hasMoveTutors()) {
            return;
        }
        int moveCount = romEntry.getValue("MoveTutorMoves");
        int offset = romEntry.getValue("MoveTutorCompatibility");
        int bytesRequired = ((moveCount + 7) & ~7) / 8;
        for (Map.Entry<Pokemon, boolean[]> compatEntry : compatData.entrySet()) {
            Pokemon pkmn = compatEntry.getKey();
            boolean[] flags = compatEntry.getValue();
            int compatOffset = offset + pkmn.speciesNumber * bytesRequired;
            for (int j = 0; j < bytesRequired; j++) {
                rom[compatOffset + j] = getByteFromFlags(flags, j * 8 + 1);
            }
        }
    }

    @Override
    public String getROMName() {
        return romEntry.name + (this.isRomHack ? " (ROM Hack)" : "");
    }

    @Override
    public String getROMCode() {
        return romEntry.romCode;
    }

    @Override
    public String getSupportLevel() {
        return (romEntry.getValue("StaticPokemonSupport") > 0) ? "Complete" : "No Static Pokemon";
    }

    // For dynamic offsets later
    private int find(String hexString) {
        return find(rom, hexString);
    }

    private static int find(byte[] haystack, String hexString) {
        if (hexString.length() % 2 != 0) {
            return -3; // error
        }
        byte[] searchFor = new byte[hexString.length() / 2];
        for (int i = 0; i < searchFor.length; i++) {
            searchFor[i] = (byte) Integer.parseInt(hexString.substring(i * 2, i * 2 + 2), 16);
        }
        List<Integer> found = RomFunctions.search(haystack, searchFor);
        if (found.size() == 0) {
            return -1; // not found
        } else if (found.size() > 1) {
            return -2; // not unique
        } else {
            return found.get(0);
        }
    }

    private List<Integer> findMultiple(String hexString) {
        return findMultiple(rom, hexString);
    }

    private static List<Integer> findMultiple(byte[] haystack, String hexString) {
        if (hexString.length() % 2 != 0) {
            return new ArrayList<Integer>(); // error
        }
        byte[] searchFor = new byte[hexString.length() / 2];
        for (int i = 0; i < searchFor.length; i++) {
            searchFor[i] = (byte) Integer.parseInt(hexString.substring(i * 2, i * 2 + 2), 16);
        }
        List<Integer> found = RomFunctions.search(haystack, searchFor);
        return found;
    }

    private void writeHexString(String hexString, int offset) {
        if (hexString.length() % 2 != 0) {
            return; // error
        }
        for (int i = 0; i < hexString.length() / 2; i++) {
            rom[offset + i] = (byte) Integer.parseInt(hexString.substring(i * 2, i * 2 + 2), 16);
        }
    }

    private void attemptObedienceEvolutionPatches() {
        if (havePatchedObedience) {
            return;
        }

        havePatchedObedience = true;
        // This routine *appears* to only exist in E/FR/LG...
        // Look for the deoxys part which is
        // MOVS R1, 0x19A
        // CMP R0, R1
        // BEQ <mew/deoxys case>
        // Hex is CD214900 8842 0FD0
        int deoxysObOffset = find(EmeraldEXConstants.deoxysObeyCode);
        if (deoxysObOffset > 0) {
            // We found the deoxys check...
            // Replacing it with MOVS R1, 0x0 would work fine.
            // This would make it so species 0x0 (glitch only) would disobey.
            // But MOVS R1, 0x0 (the version I know) is 2-byte
            // So we just use it twice...
            // the equivalent of nop'ing the second time.
            rom[deoxysObOffset] = 0x00;
            rom[deoxysObOffset + 1] = EmeraldEXConstants.gbaSetRxOpcode | EmeraldEXConstants.gbaR1;
            rom[deoxysObOffset + 2] = 0x00;
            rom[deoxysObOffset + 3] = EmeraldEXConstants.gbaSetRxOpcode | EmeraldEXConstants.gbaR1;
            // Look for the mew check too... it's 0x16 ahead
            if (readWord(deoxysObOffset
                    + EmeraldEXConstants.mewObeyOffsetFromDeoxysObey) == (((EmeraldEXConstants.gbaCmpRxOpcode
                            | EmeraldEXConstants.gbaR0) << 8) | (EmeraldEXConstants.mewIndex))) {
                // Bingo, thats CMP R0, 0x97
                // change to CMP R0, 0x0
                writeWord(deoxysObOffset + EmeraldEXConstants.mewObeyOffsetFromDeoxysObey,
                        (((EmeraldEXConstants.gbaCmpRxOpcode | EmeraldEXConstants.gbaR0) << 8) | (0)));
            }
        }
    }

    private void patchForNationalDex() {
        log("--Patching for National Dex at Start of Game--");
        String nl = System.getProperty("line.separator");
        int fso = romEntry.getValue("FreeSpace");
        // Find the original pokedex script
        int pkDexOffset = find(EmeraldEXConstants.ePokedexScriptIdentifier);
        if (pkDexOffset < 0) {
            log("Patch unsuccessful." + nl);
            return;
        }
        int textPointer = readPointer(pkDexOffset - 4);
        int realScriptLocation = pkDexOffset - 8;
        int pointerLocToScript = find(pointerToHexString(realScriptLocation));
        if (pointerLocToScript < 0) {
            log("Patch unsuccessful." + nl);
            return;
        }
        // Find free space for our new routine
        int writeSpace = RomFunctions.freeSpaceFinder(rom, EmeraldEXConstants.freeSpaceByte, 27, fso);
        if (writeSpace < fso) {
            // Somehow this ROM is full
            log("Patch unsuccessful." + nl);
            return;
        }
        writePointer(pointerLocToScript, writeSpace);
        writeHexString(EmeraldEXConstants.eNatDexScriptPart1, writeSpace);
        writePointer(writeSpace + 4, textPointer);
        writeHexString(EmeraldEXConstants.eNatDexScriptPart2, writeSpace + 8);
        log("Patch successful!" + nl);
    }

    public String pointerToHexString(int pointer) {
        String hex = String.format("%08X", pointer + 0x08000000);
        return new String(new char[] { hex.charAt(6), hex.charAt(7), hex.charAt(4), hex.charAt(5), hex.charAt(2),
                hex.charAt(3), hex.charAt(0), hex.charAt(1) });
    }

    private void populateEvolutions() {
        for (Pokemon pkmn : pokes) {
            if (pkmn != null) {
                pkmn.evolutionsFrom.clear();
                pkmn.evolutionsTo.clear();
            }
        }

        int baseOffset = romEntry.getValue("PokemonEvolutions");
        int numInternalPokes = romEntry.getValue("PokemonCount");
        for (int i = 1; i <= numRealPokemon; i++) {
            Pokemon pk = pokemonList.get(i);
            int idx = pk.speciesNumber;
            int evoOffset = baseOffset + (idx) * 0x50;
            for (int j = 0; j < 10; j++) {
                int method = readWord(evoOffset + j * 8);
                int evolvingTo = readWord(evoOffset + j * 8 + 4);
                if (evolvingTo >= 1 && evolvingTo <= numInternalPokes && EvolutionType.fromIndex(EvolutionDataVersion.EMERALD_EX, method) != null) {
                    int extraInfo = readWord(evoOffset + j * 8 + 2);
                    EvolutionType et = EvolutionType.fromIndex(EvolutionDataVersion.EMERALD_EX, method);
                    Evolution evo = new Evolution(pk, pokesInternal[evolvingTo], true, et, extraInfo);
                    if (!pk.evolutionsFrom.contains(evo)) {
                        pk.evolutionsFrom.add(evo);
                        pokesInternal[evolvingTo].evolutionsTo.add(evo);
                    }
                }
            }
            // split evos don't carry stats
            if (pk.evolutionsFrom.size() > 1) {
                for (Evolution e : pk.evolutionsFrom) {
                    e.carryStats = false;
                }
            }
        }
    }

    private void writeEvolutions() {
        int baseOffset = romEntry.getValue("PokemonEvolutions");
        for (int i = 1; i <= numRealPokemon; i++) {
            Pokemon pk = pokemonList.get(i);
            int idx = pk.speciesNumber;
            int evoOffset = baseOffset + (idx) * 0x50;
            int evosWritten = 0;
            for (Evolution evo : pk.evolutionsFrom) {
                writeWord(evoOffset, evo.type.toIndex(EvolutionDataVersion.EMERALD_EX));
                writeWord(evoOffset + 2, evo.extraInfo);
                writeWord(evoOffset + 4, evo.to.speciesNumber);
                writeWord(evoOffset + 6, 0);
                evoOffset += 8;
                evosWritten++;
                if (evosWritten == 10) {
                    break;
                }
            }
            while (evosWritten < 10) {
                writeWord(evoOffset, 0);
                writeWord(evoOffset + 2, 0);
                writeWord(evoOffset + 4, 0);
                writeWord(evoOffset + 6, 0);
                evoOffset += 8;
                evosWritten++;
            }
        }
    }

    @Override
    public void removeTradeEvolutions(boolean changeMoveEvos) {
        attemptObedienceEvolutionPatches();

        // no move evos, so no need to check for those
        log("--Removing Trade Evolutions--");
        Set<Evolution> extraEvolutions = new HashSet<Evolution>();
        for (Pokemon pkmn : pokes) {
            if (pkmn != null) {
                extraEvolutions.clear();
                for (Evolution evo : pkmn.evolutionsFrom) {
                    // Pure Trade
                    if (evo.type == EvolutionType.TRADE) {
                        // Haunter, Machoke, Kadabra, Graveler
                        // Make it into level 37, we're done.
                        evo.type = EvolutionType.LEVEL;
                        evo.extraInfo = 37;
                        logEvoChangeLevel(evo.from.name, evo.to.name, 37);
                    }
                    // Trade w/ Held Item
                    if (evo.type == EvolutionType.TRADE_ITEM) {
                        // Get the current item & evolution
                        int item = evo.extraInfo;
                        if (evo.from.number == EmeraldEXConstants.slowpokeIndex) {
                            // Slowpoke is awkward - he already has a level evo
                            // So we can't do Level up w/ Held Item for him
                            // Put Water Stone instead
                            evo.type = EvolutionType.STONE;
                            evo.extraInfo = EmeraldEXConstants.waterStoneIndex; // water
                                                                                // stone
                            logEvoChangeStone(evo.from.name, evo.to.name,
                                    itemNames[EmeraldEXConstants.waterStoneIndex]);
                        } else {
                            logEvoChangeLevelWithItem(evo.from.name, evo.to.name, itemNames[item]);
                            // Replace, for this entry, w/
                            // Level up w/ Held Item at Day
                            evo.type = EvolutionType.LEVEL_ITEM_DAY;
                            // now add an extra evo for
                            // Level up w/ Held Item at Night
                            Evolution extraEntry = new Evolution(evo.from, evo.to, true, EvolutionType.LEVEL_ITEM_NIGHT,
                                    item);
                            extraEvolutions.add(extraEntry);
                        }
                    }
                }

                pkmn.evolutionsFrom.addAll(extraEvolutions);
                for (Evolution ev : extraEvolutions) {
                    ev.to.evolutionsTo.add(ev);
                }
            }
        }
        logBlankLine();
    }

    @Override
    public boolean canChangeTrainerText() {
        return true;
    }

    @Override
    public List<String> getTrainerNames() {
        int baseOffset = romEntry.getValue("TrainerData");
        int amount = romEntry.getValue("TrainerCount");
        int entryLen = romEntry.getValue("TrainerEntrySize");
        List<String> theTrainers = new ArrayList<String>();
        for (int i = 1; i < amount; i++) {
            theTrainers.add(readVariableLengthString(baseOffset + i * entryLen + 4));
        }
        return theTrainers;
    }

    @Override
    public void setTrainerNames(List<String> trainerNames) {
        int baseOffset = romEntry.getValue("TrainerData");
        int amount = romEntry.getValue("TrainerCount");
        int entryLen = romEntry.getValue("TrainerEntrySize");
        int nameLen = romEntry.getValue("TrainerNameLength");
        Iterator<String> theTrainers = trainerNames.iterator();
        for (int i = 1; i < amount; i++) {
            String newName = theTrainers.next();
            writeFixedLengthString(newName, baseOffset + i * entryLen + 4, nameLen);
        }

    }

    @Override
    public TrainerNameMode trainerNameMode() {
        return TrainerNameMode.MAX_LENGTH;
    }

    @Override
    public List<Integer> getTCNameLengthsByTrainer() {
        // not needed
        return new ArrayList<Integer>();
    }

    @Override
    public int maxTrainerNameLength() {
        return romEntry.getValue("TrainerNameLength") - 1;
    }

    @Override
    public List<String> getTrainerClassNames() {
        int baseOffset = romEntry.getValue("TrainerClassNames");
        int amount = romEntry.getValue("TrainerClassCount");
        int length = romEntry.getValue("TrainerClassNameLength");
        List<String> trainerClasses = new ArrayList<String>();
        for (int i = 0; i < amount; i++) {
            trainerClasses.add(readVariableLengthString(baseOffset + i * length));
        }
        return trainerClasses;
    }

    @Override
    public void setTrainerClassNames(List<String> trainerClassNames) {
        int baseOffset = romEntry.getValue("TrainerClassNames");
        int amount = romEntry.getValue("TrainerClassCount");
        int length = romEntry.getValue("TrainerClassNameLength");
        Iterator<String> trainerClasses = trainerClassNames.iterator();
        for (int i = 0; i < amount; i++) {
            writeFixedLengthString(trainerClasses.next(), baseOffset + i * length, length);
        }
    }

    @Override
    public int maxTrainerClassNameLength() {
        return romEntry.getValue("TrainerClassNameLength") - 1;
    }

    @Override
    public boolean fixedTrainerClassNamesLength() {
        return false;
    }

    @Override
    public List<Integer> getDoublesTrainerClasses() {
        int[] doublesClasses = romEntry.arrayEntries.get("DoublesTrainerClasses");
        List<Integer> doubles = new ArrayList<Integer>();
        for (int tClass : doublesClasses) {
            doubles.add(tClass);
        }
        return doubles;
    }

    @Override
    public boolean canChangeStaticPokemon() {
        return (romEntry.getValue("StaticPokemonSupport") > 0);
    }

    @Override
    public String getDefaultExtension() {
        return "gba";
    }

    @Override
    public int abilitiesPerPokemon() {
        return 3;
    }

    @Override
    public int highestAbilityIndex() {
        return EmeraldEXConstants.highestAbilityIndex;
    }

    private void loadAbilityNames() {
        int nameoffs = romEntry.getValue("AbilityNames");
        int namelen = romEntry.getValue("AbilityNameLength");
        abilityNames = new String[EmeraldEXConstants.highestAbilityIndex + 1];
        for (int i = 0; i <= EmeraldEXConstants.highestAbilityIndex; i++) {
            abilityNames[i] = readFixedLengthString(nameoffs + namelen * i, namelen);
        }
    }

    @Override
    public String abilityName(int number) {
        return abilityNames[number];
    }

    @Override
    public int internalStringLength(String string) {
        return translateString(string).length;
    }

    @Override
    public void applySignature() {
        // Emerald, intro sprite: any Pokemon.
        int introPokemon = randomPokemon().speciesNumber;
        writeWord(romEntry.getValue("IntroSpriteOffset"), introPokemon);
        writeWord(romEntry.getValue("IntroCryOffset"), introPokemon);
    }

    private Pokemon randomPokemonLimited(int maxValue, boolean blockNonMales) {
        checkPokemonRestrictions();
        List<Pokemon> validPokemon = new ArrayList<Pokemon>();
        for (Pokemon pk : this.mainPokemonList) {
            if (pk.speciesNumber <= maxValue && (!blockNonMales || pk.genderRatio <= 0xFD)) {
                validPokemon.add(pk);
            }
        }
        if (validPokemon.size() == 0) {
            return null;
        } else {
            return validPokemon.get(random.nextInt(validPokemon.size()));
        }
    }

    private void determineMapBankSizes() {
        int mbpsOffset = romEntry.getValue("MapHeaders");
        List<Integer> mapBankOffsets = new ArrayList<Integer>();

        int offset = mbpsOffset;

        // find map banks
        while (true) {
            boolean valid = true;
            for (int mbOffset : mapBankOffsets) {
                if (mbpsOffset < mbOffset && offset >= mbOffset) {
                    valid = false;
                    break;
                }
            }
            if (!valid) {
                break;
            }
            int newMBOffset = readPointer(offset);
            if (newMBOffset < 0 || newMBOffset >= rom.length) {
                break;
            }
            mapBankOffsets.add(newMBOffset);
            offset += 4;
        }
        int bankCount = mapBankOffsets.size();
        int[] bankMapCounts = new int[bankCount];
        for (int bank = 0; bank < bankCount; bank++) {
            int baseBankOffset = mapBankOffsets.get(bank);
            int count = 0;
            offset = baseBankOffset;
            while (true) {
                boolean valid = true;
                for (int mbOffset : mapBankOffsets) {
                    if (baseBankOffset < mbOffset && offset >= mbOffset) {
                        valid = false;
                        break;
                    }
                }
                if (!valid) {
                    break;
                }
                if (baseBankOffset < mbpsOffset && offset >= mbpsOffset) {
                    break;
                }
                int newMapOffset = readPointer(offset);
                if (newMapOffset < 0 || newMapOffset >= rom.length) {
                    break;
                }
                count++;
                offset += 4;
            }
            bankMapCounts[bank] = count;
        }

        romEntry.entries.put("MapBankCount", bankCount);
        romEntry.arrayEntries.put("MapBankSizes", bankMapCounts);
    }

    private class ItemLocationInner {
        private int mapBank;
        private int mapNumber;
        private int x;
        private int y;
        private int offset;
        private boolean hidden;

        public ItemLocationInner(int mapBank, int mapNumber, int x, int y, int offset, boolean hidden) {
            super();
            this.mapBank = mapBank;
            this.mapNumber = mapNumber;
            this.x = x;
            this.y = y;
            this.offset = offset;
            this.hidden = hidden;
        }

        @Override
        public String toString() {
            return String.format("%s (%d.%d) @ %d, %d (%s)", mapNames[mapBank][mapNumber], mapBank, mapNumber, x, y,
                    hidden ? "hidden" : "visible");
        }
    }

    private void preprocessMaps() {
        itemOffs = new ArrayList<ItemLocationInner>();
        int bankCount = romEntry.getValue("MapBankCount");
        int[] bankMapCounts = romEntry.arrayEntries.get("MapBankSizes");
        int itemBall = romEntry.getValue("ItemBallPic");
        mapNames = new String[bankCount][];
        int mbpsOffset = romEntry.getValue("MapHeaders");
        int mapLabels = romEntry.getValue("MapLabels");
        Map<Integer, String> mapLabelsM = new HashMap<Integer, String>();
        for (int bank = 0; bank < bankCount; bank++) {
            int bankOffset = readPointer(mbpsOffset + bank * 4);
            mapNames[bank] = new String[bankMapCounts[bank]];
            for (int map = 0; map < bankMapCounts[bank]; map++) {
                int mhOffset = readPointer(bankOffset + map * 4);

                // map name
                int mapLabel = rom[mhOffset + 0x14] & 0xFF;
                if (mapLabelsM.containsKey(mapLabel)) {
                    mapNames[bank][map] = mapLabelsM.get(mapLabel);
                } else {
                    mapNames[bank][map] = readVariableLengthString(readPointer(mapLabels + mapLabel * 8 + 4));
                    mapLabelsM.put(mapLabel, mapNames[bank][map]);
                }

                // events
                int eventOffset = readPointer(mhOffset + 4);
                if (eventOffset >= 0 && eventOffset < rom.length) {

                    int pCount = rom[eventOffset] & 0xFF;
                    int spCount = rom[eventOffset + 3] & 0xFF;

                    if (pCount > 0) {
                        int peopleOffset = readPointer(eventOffset + 4);
                        for (int p = 0; p < pCount; p++) {
                            int pSprite = rom[peopleOffset + p * 24 + 1];
                            if (pSprite == itemBall && readPointer(peopleOffset + p * 24 + 16) >= 0) {
                                // Get script and look inside
                                int scriptOffset = readPointer(peopleOffset + p * 24 + 16);
                                if (rom[scriptOffset] == 0x1A && rom[scriptOffset + 1] == 0x00
                                        && (rom[scriptOffset + 2] & 0xFF) == 0x80 && rom[scriptOffset + 5] == 0x1A
                                        && rom[scriptOffset + 6] == 0x01 && (rom[scriptOffset + 7] & 0xFF) == 0x80
                                        && rom[scriptOffset + 10] == 0x09
                                        && (rom[scriptOffset + 11] == 0x00 || rom[scriptOffset + 11] == 0x01)) {
                                    // item ball script
                                    itemOffs.add(new ItemLocationInner(bank, map, readWord(peopleOffset + p * 24 + 4),
                                            readWord(peopleOffset + p * 24 + 6), scriptOffset + 3, false));
                                }
                            }
                        }
                        // TM Text?
                        for (TMOrMTTextEntry tte : romEntry.tmmtTexts) {
                            if (tte.mapBank == bank && tte.mapNumber == map) {
                                // process this one
                                int scriptOffset = readPointer(peopleOffset + (tte.personNum - 1) * 24 + 16);
                                if (scriptOffset >= 0) {
                                    int lookAt = scriptOffset + tte.offsetInScript;
                                    // make sure this actually looks like a text
                                    // pointer
                                    if (lookAt >= 0 && lookAt < rom.length - 2) {
                                        if (rom[lookAt + 3] == 0x08 || rom[lookAt + 3] == 0x09) {
                                            // okay, it passes the basic test
                                            tte.actualOffset = lookAt;
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (spCount > 0) {
                        int signpostsOffset = readPointer(eventOffset + 16);
                        for (int sp = 0; sp < spCount; sp++) {
                            int spType = rom[signpostsOffset + sp * 12 + 5];
                            if (spType >= 5 && spType <= 7) {
                                // hidden item
                                int itemHere = readWord(signpostsOffset + sp * 12 + 8);
                                if (itemHere != 0) {
                                    // itemid 0 is coins
                                    itemOffs.add(new ItemLocationInner(bank, map, readWord(signpostsOffset + sp * 12),
                                            readWord(signpostsOffset + sp * 12 + 2), signpostsOffset + sp * 12 + 8,
                                            true));
                                }
                            }
                        }
                    }
                }
            }
        }
        // Plotless items
        for (int itemOff : romEntry.plotlessItems) {
            itemOffs.add(new ItemLocationInner(-1, -1, -1, -1, itemOff, false));
        }
    }

    @Override
    public ItemList getAllowedItems() {
        return allowedItems;
    }

    @Override
    public ItemList getNonBadItems() {
        return nonBadItems;
    }

    private void loadItemNames() {
        int nameoffs = romEntry.getValue("ItemData");
        int structlen = romEntry.getValue("ItemEntrySize");
        int maxcount = romEntry.getValue("ItemCount");
        itemNames = new String[maxcount + 1];
        for (int i = 0; i <= maxcount; i++) {
            itemNames[i] = readVariableLengthString(nameoffs + structlen * i);
        }
    }

    @Override
    public String[] getItemNames() {
        return itemNames;
    }

    @Override
    public List<Integer> getRequiredFieldTMs() {
        // emerald has a few TMs from pickup
        return EmeraldEXConstants.eRequiredFieldTMs;
    }

    @Override
    public List<FieldTM> getCurrentFieldTMs() {
        if (!mapLoadingDone) {
            preprocessMaps();
            mapLoadingDone = true;
        }
        List<FieldTM> fieldTMs = new ArrayList<FieldTM>();

        for (ItemLocationInner il : itemOffs) {
            int itemHere = readWord(il.offset);
            if (EmeraldEXConstants.allowedItems.isTM(itemHere)) {
                int thisTM = itemHere - EmeraldEXConstants.tmItemOffset + 1;
                // hack for repeat TMs
                FieldTM tmObj = new FieldTM(il.toString(), thisTM);
                if (fieldTMs.contains(tmObj) == false) {
                    fieldTMs.add(tmObj);
                }
            }
        }
        return fieldTMs;
    }

    @Override
    public void setFieldTMs(List<Integer> fieldTMs) {
        if (!mapLoadingDone) {
            preprocessMaps();
            mapLoadingDone = true;
        }
        Iterator<Integer> iterTMs = fieldTMs.iterator();
        int[] givenTMs = new int[512];

        for (ItemLocationInner il : itemOffs) {
            int itemHere = readWord(il.offset);
            if (EmeraldEXConstants.allowedItems.isTM(itemHere)) {
                // Cache replaced TMs to duplicate repeats
                if (givenTMs[itemHere] != 0) {
                    writeWord(il.offset, givenTMs[itemHere]);
                } else {
                    // Replace this with a TM from the list
                    int tm = iterTMs.next();
                    tm += EmeraldEXConstants.tmItemOffset - 1;
                    givenTMs[itemHere] = tm;
                    writeWord(il.offset, tm);
                }
            }
        }
    }

    @Override
    public List<ItemLocation> getRegularFieldItems() {
        if (!mapLoadingDone) {
            preprocessMaps();
            mapLoadingDone = true;
        }
        List<ItemLocation> fieldItems = new ArrayList<ItemLocation>();

        for (ItemLocationInner il : itemOffs) {
            int itemHere = readWord(il.offset);
            if (il.mapBank == -1 /* is plotless key item */ || (EmeraldEXConstants.allowedItems.isAllowed(itemHere)
                    && !(EmeraldEXConstants.allowedItems.isTM(itemHere)))) {
                fieldItems.add(new ItemLocation(il.toString(), itemHere));
            }
        }
        return fieldItems;
    }

    @Override
    public void setRegularFieldItems(List<Integer> items) {
        if (!mapLoadingDone) {
            preprocessMaps();
            mapLoadingDone = true;
        }
        Iterator<Integer> iterItems = items.iterator();

        for (ItemLocationInner il : itemOffs) {
            int itemHere = readWord(il.offset);
            if (EmeraldEXConstants.allowedItems.isAllowed(itemHere)
                    && !(EmeraldEXConstants.allowedItems.isTM(itemHere))) {
                // Replace it
                writeWord(il.offset, iterItems.next());
            }
        }

    }

    @Override
    public List<IngameTrade> getIngameTrades() {
        List<IngameTrade> trades = new ArrayList<IngameTrade>();

        // info
        int tableOffset = romEntry.getValue("TradeTableOffset");
        int tableSize = romEntry.getValue("TradeTableSize");
        int[] unused = romEntry.arrayEntries.get("TradesUnused");
        int unusedOffset = 0;
        int entryLength = 60;

        for (int entry = 0; entry < tableSize; entry++) {
            if (unusedOffset < unused.length && unused[unusedOffset] == entry) {
                unusedOffset++;
                continue;
            }
            IngameTrade trade = new IngameTrade();
            int entryOffset = tableOffset + entry * entryLength;
            trade.nickname = readVariableLengthString(entryOffset);
            trade.givenPokemon = pokesInternal[readWord(entryOffset + 12)];
            trade.ivs = new int[6];
            for (int i = 0; i < 6; i++) {
                trade.ivs[i] = rom[entryOffset + 14 + i] & 0xFF;
            }
            trade.otId = readWord(entryOffset + 24);
            trade.item = readWord(entryOffset + 40);
            trade.otName = readVariableLengthString(entryOffset + 43);
            trade.requestedPokemon = pokesInternal[readWord(entryOffset + 56)];
            trades.add(trade);
        }

        return trades;

    }

    @Override
    public void setIngameTrades(List<IngameTrade> trades) {
        // info
        int tableOffset = romEntry.getValue("TradeTableOffset");
        int tableSize = romEntry.getValue("TradeTableSize");
        int[] unused = romEntry.arrayEntries.get("TradesUnused");
        int unusedOffset = 0;
        int entryLength = 60;
        int tradeOffset = 0;

        for (int entry = 0; entry < tableSize; entry++) {
            if (unusedOffset < unused.length && unused[unusedOffset] == entry) {
                unusedOffset++;
                continue;
            }
            IngameTrade trade = trades.get(tradeOffset++);
            int entryOffset = tableOffset + entry * entryLength;
            writeFixedLengthString(trade.nickname, entryOffset, 12);
            writeWord(entryOffset + 12, trade.givenPokemon.speciesNumber);
            for (int i = 0; i < 6; i++) {
                rom[entryOffset + 14 + i] = (byte) trade.ivs[i];
            }
            writeWord(entryOffset + 24, trade.otId);
            writeWord(entryOffset + 40, trade.item);
            writeFixedLengthString(trade.otName, entryOffset + 43, 11);
            writeWord(entryOffset + 56, trade.requestedPokemon.speciesNumber);
        }
    }

    @Override
    public boolean hasDVs() {
        return false;
    }

    @Override
    public int generationOfPokemon() {
        return 3;
    }

    @Override
    public void removeEvosForPokemonPool() {
        List<Pokemon> pokemonIncluded = this.mainPokemonList;
        Set<Evolution> keepEvos = new HashSet<Evolution>();
        for (Pokemon pk : pokes) {
            if (pk != null) {
                keepEvos.clear();
                for (Evolution evol : pk.evolutionsFrom) {
                    if (pokemonIncluded.contains(evol.from) && pokemonIncluded.contains(evol.to)) {
                        keepEvos.add(evol);
                    } else {
                        evol.to.evolutionsTo.remove(evol);
                    }
                }
                pk.evolutionsFrom.retainAll(keepEvos);
            }
        }
    }

    @Override
    public boolean supportsFourStartingMoves() {
        return true;
    }

    @Override
    public List<Integer> getFieldMoves() {
        // cut, fly, surf, strength, flash,
        // dig, teleport, waterfall,
        // rock smash, sweet scent
        // not softboiled or milk drink
        // dive and secret power in RSE only
        return EmeraldEXConstants.rseFieldMoves;
    }

    @Override
    public List<Integer> getEarlyRequiredHMMoves() {
        // RSE: rock smash
        // FRLG: cut
        return EmeraldEXConstants.rseEarlyRequiredHMMoves;
    }

    @Override
    public int miscTweaksAvailable() {
        int available = MiscTweak.LOWER_CASE_POKEMON_NAMES.getValue();
        available |= MiscTweak.NATIONAL_DEX_AT_START.getValue();
        if (romEntry.getValue("RunIndoorsTweakOffset") > 0) {
            available |= MiscTweak.RUNNING_SHOES_INDOORS.getValue();
        }
        if (romEntry.getValue("TextSpeedValuesOffset") > 0 || romEntry.codeTweaks.get("InstantTextTweak") != null) {
            available |= MiscTweak.FASTEST_TEXT.getValue();
        }
        if (romEntry.getValue("CatchingTutorialOpponentMonOffset") > 0
                || romEntry.getValue("CatchingTutorialPlayerMonOffset") > 0) {
            available |= MiscTweak.RANDOMIZE_CATCHING_TUTORIAL.getValue();
        }
        if (romEntry.getValue("PCPotionOffset") != 0) {
            available |= MiscTweak.RANDOMIZE_PC_POTION.getValue();
        }
        available |= MiscTweak.BAN_LUCKY_EGG.getValue();
        return available;
    }

    @Override
    public void applyMiscTweak(MiscTweak tweak) {
        if (tweak == MiscTweak.RUNNING_SHOES_INDOORS) {
            applyRunningShoesIndoorsPatch();
        } else if (tweak == MiscTweak.FASTEST_TEXT) {
            applyFastestTextPatch();
        } else if (tweak == MiscTweak.LOWER_CASE_POKEMON_NAMES) {
            applyCamelCaseNames();
        } else if (tweak == MiscTweak.NATIONAL_DEX_AT_START) {
            patchForNationalDex();
        } else if (tweak == MiscTweak.RANDOMIZE_CATCHING_TUTORIAL) {
            randomizeCatchingTutorial();
        } else if (tweak == MiscTweak.BAN_LUCKY_EGG) {
            allowedItems.banSingles(EmeraldEXConstants.luckyEggIndex);
            nonBadItems.banSingles(EmeraldEXConstants.luckyEggIndex);
        } else if (tweak == MiscTweak.RANDOMIZE_PC_POTION) {
            randomizePCPotion();
        }
    }

    private void randomizeCatchingTutorial() {
        if (romEntry.getValue("CatchingTutorialOpponentMonOffset") > 0) {
            int oppOffset = romEntry.getValue("CatchingTutorialOpponentMonOffset");
            Pokemon opponent = randomPokemonLimited(510, true);
            if (opponent != null) {
                int oppValue = opponent.speciesNumber;
                if (oppValue > 255) {
                    rom[oppOffset] = (byte) 0xFF;
                    rom[oppOffset + 1] = EmeraldEXConstants.gbaSetRxOpcode | EmeraldEXConstants.gbaR1;

                    rom[oppOffset + 2] = (byte) (oppValue - 0xFF);
                    rom[oppOffset + 3] = EmeraldEXConstants.gbaAddRxOpcode | EmeraldEXConstants.gbaR1;
                } else {
                    rom[oppOffset] = (byte) oppValue;
                    rom[oppOffset + 1] = EmeraldEXConstants.gbaSetRxOpcode | EmeraldEXConstants.gbaR1;

                    writeWord(oppOffset + 2, EmeraldEXConstants.gbaNopOpcode);
                }
            }
        }

        if (romEntry.getValue("CatchingTutorialPlayerMonOffset") > 0) {
            int playerOffset = romEntry.getValue("CatchingTutorialPlayerMonOffset");
            Pokemon playerMon = randomPokemonLimited(510, false);
            if (playerMon != null) {
                int plyValue = playerMon.speciesNumber;
                if (plyValue > 255) {
                    rom[playerOffset] = (byte) 0xFF;
                    rom[playerOffset + 1] = EmeraldEXConstants.gbaSetRxOpcode | EmeraldEXConstants.gbaR1;

                    rom[playerOffset + 2] = (byte) (plyValue - 0xFF);
                    rom[playerOffset + 3] = EmeraldEXConstants.gbaAddRxOpcode | EmeraldEXConstants.gbaR1;
                } else {
                    rom[playerOffset] = (byte) plyValue;
                    rom[playerOffset + 1] = EmeraldEXConstants.gbaSetRxOpcode | EmeraldEXConstants.gbaR1;

                    writeWord(playerOffset + 2, EmeraldEXConstants.gbaNopOpcode);
                }
            }
        }

    }

    private void applyRunningShoesIndoorsPatch() {
        if (romEntry.getValue("RunIndoorsTweakOffset") != 0) {
            rom[romEntry.getValue("RunIndoorsTweakOffset")] = 0x00;
        }
    }

    private void applyFastestTextPatch() {
        if (romEntry.codeTweaks.get("InstantTextTweak") != null) {
            try {
                FileFunctions.applyPatch(rom, romEntry.codeTweaks.get("InstantTextTweak"));
            } catch (IOException e) {
                throw new RandomizerIOException(e);
            }
        } else if (romEntry.getValue("TextSpeedValuesOffset") > 0) {
            int tsvOffset = romEntry.getValue("TextSpeedValuesOffset");
            rom[tsvOffset] = 4; // slow = medium
            rom[tsvOffset + 1] = 1; // medium = fast
            rom[tsvOffset + 2] = 0; // fast = instant
        }
    }

    private void randomizePCPotion() {
        if (romEntry.getValue("PCPotionOffset") != 0) {
            writeWord(romEntry.getValue("PCPotionOffset"), this.getNonBadItems().randomNonTM(this.random));
        }
    }

    @Override
    public boolean isROMHack() {
        return this.isRomHack;
    }

    @Override
    public BufferedImage getMascotImage() {
        Pokemon mascotPk = randomPokemon();
        int mascotPokemon = mascotPk.speciesNumber;
        int frontSprites = romEntry.getValue("FrontSprites");
        int palettes = romEntry.getValue("PokemonPalettes");
        int fsOffset = readPointer(frontSprites + mascotPokemon * 8);
        int palOffset = readPointer(palettes + mascotPokemon * 8);

        byte[] trueFrontSprite = DSDecmp.Decompress(rom, fsOffset);
        byte[] truePalette = DSDecmp.Decompress(rom, palOffset);

        // Convert palette into RGB
        int[] convPalette = new int[16];
        // Leave palette[0] as 00000000 for transparency
        for (int i = 0; i < 15; i++) {
            int palValue = readWord(truePalette, i * 2 + 2);
            convPalette[i + 1] = GFXFunctions.conv16BitColorToARGB(palValue);
        }

        // Make image, 4bpp
        BufferedImage bim = GFXFunctions.drawTiledImage(trueFrontSprite, convPalette, 64, 64, 4);
        return bim;
    }

    @Override
    public void writeCheckValueToROM(int value) {
        if (romEntry.getValue("CheckValueOffset") > 0) {
            int cvOffset = romEntry.getValue("CheckValueOffset");
            for (int i = 0; i < 4; i++) {
                rom[cvOffset + i] = (byte) ((value >> (3 - i) * 8) & 0xFF);
            }
        }
    }
}
