package com.dabomstew.pkrandom.pokemon;

public enum EvolutionDataVersion {
    
    GEN_1(0),
    GEN_2(1),
    GEN_3(2),
    GEN_4(3),
    GEN_5(4),
    EMERALD_EX(5);
    
    protected int index;
    
    private EvolutionDataVersion(int index) {
        this.index = index;
    }

}
