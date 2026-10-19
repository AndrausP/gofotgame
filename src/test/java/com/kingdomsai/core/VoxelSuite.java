package com.kingdomsai.core;

/**
 * Suíte do jogo em Godot (Core + mundo voxel + ponte): geração, mundo que se mexe, salvamento, leis e a ponte pela rede.
 * Rodar: ./gradlew voxelTest   (ou java -cp <classes>;gson.jar com.kingdomsai.core.VoxelSuite)
 */
public final class VoxelSuite {
    public static void main(String[] args) throws Exception {
        int ok = 0, bad = 0;
        int[][] rs = {VoxelSelfTest.run(), WorldSimSelfTest.run(), SaveSelfTest.run(), LawSelfTest.run(), BridgeSelfTest.run()};
        for (int[] r : rs) {
            ok += r[0];
            bad += r[1];
        }
        System.out.println("\nSuíte voxel: " + ok + " passaram, " + bad + " falharam.");
        System.exit(bad == 0 ? 0 : 1);
    }
}
