package com.spotifry.desktop

import com.spotifry.music.player.FineTuneSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoundTuneTest {
    @Test fun neutralAddsNothing() = assertNull(Sound.tune(FineTuneSetting()))

    @Test fun widthAndBalanceAreOnePanWithThePhonesMaths() {
        // 150% wide: each side keeps 1.25 of itself and takes away 0.25 of the other; balance right 60% turns the left down to 0.4
        assertEquals(
            "lavfi=[aformat=channel_layouts=stereo,pan=stereo|c0=0.5000*c0-0.1000*c1|c1=1.2500*c1-0.2500*c0]",
            Sound.tune(FineTuneSetting(width = 150, balance = 60)),
        )
        assertEquals("lavfi=[aformat=channel_layouts=stereo,pan=stereo|c0=0.5000*c0+0.5000*c1|c1=0.5000*c1+0.5000*c0]", Sound.tune(FineTuneSetting(width = 0)))
    }

    @Test fun filtersGainNightModeAndTheLimiterInThePhonesOrder() {
        assertEquals(
            "lavfi=[highpass=f=80:p=2:t=q:w=0.707,lowpass=f=12000:p=2:t=q:w=0.707," +
                "acompressor=threshold=0.0631:ratio=2:attack=10:release=250:makeup=1.9953:knee=1:link=maximum:detection=peak," +
                "volume=3dB,alimiter=limit=0.8910:attack=1:release=100:level=0]",
            Sound.tune(FineTuneSetting(highPassHz = 80, lowPassHz = 12_000, night = 1, gainDb = 3)),
        )
        assertEquals("lavfi=[volume=-4dB,alimiter=limit=0.8910:attack=1:release=100:level=0]", Sound.tune(FineTuneSetting(gainDb = -4, limiter = true)))
    }

    @Test fun aFilterThisMpvLacksIsLeftOutAndNothingLeftMeansNoFilter() {
        assertEquals("lavfi=[volume=3dB]", Sound.tune(FineTuneSetting(gainDb = 3, limiter = true)) { it != "alimiter" })
        assertNull(Sound.tune(FineTuneSetting(limiter = true)) { false })
    }
}
