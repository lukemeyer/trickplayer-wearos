package com.lukemeyer.trickplayer.core.scene

/**
 * Turns a scene number into the frame it shows.
 *
 * There is almost nothing left here, and that is the point. Selecting which
 * frames are worth showing — stepping past near-blank frames and silent windows
 * — happens once, when [Episode] is built, so by the time anyone asks for scene
 * N the answer is a list lookup.
 *
 * The earlier shape of this, carried over from the Pebble build, skipped forward
 * at read time and had a bug that only real content exposed: skipping does not
 * pass over a frame, it takes a *later* scene's frame, and that scene then shows
 * it a second time. See the note on [Episode.scenes].
 */
object SceneResolver {

    data class Resolved(
        val sceneIndex: Int,
        val scene: Episode.SceneRef,
        val skipped: Int,
    ) {
        val frameIndex: Int get() = scene.frameIndex
    }

    /**
     * @param sceneIndex wraps at the end of the episode, so a face left running
     *   loops rather than stalling.
     */
    fun resolve(ep: Episode, sceneIndex: Int): Resolved? {
        if (ep.sceneCount == 0) return null
        val slot = Math.floorMod(sceneIndex, ep.sceneCount)
        return Resolved(sceneIndex, ep.scenes[slot], 0)
    }
}
