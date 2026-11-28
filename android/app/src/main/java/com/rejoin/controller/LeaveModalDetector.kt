package com.rejoin.controller

import kotlin.math.abs

data class ScreenFrame(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
)

data class LeaveMatch(
    val tapX: Int,
    val tapY: Int,
    val cardBounds: RectBounds,
    val buttonCount: Int,
    val source: String,
)

object LeaveModalDetector {
    private fun red(c: Int): Int = (c ushr 16) and 0xFF
    private fun green(c: Int): Int = (c ushr 8) and 0xFF
    private fun blue(c: Int): Int = c and 0xFF

    private fun luminance(c: Int): Int = (red(c) * 299 + green(c) * 587 + blue(c) * 114) / 1000

    private fun colorSpread(c: Int): Int {
        val r = red(c)
        val g = green(c)
        val b = blue(c)
        return maxOf(r, maxOf(g, b)) - minOf(r, minOf(g, b))
    }

    private fun isNeutral(c: Int, maxSpread: Int): Boolean = colorSpread(c) <= maxSpread

    private fun isWhiteButtonBg(c: Int): Boolean = luminance(c) >= 195 && isNeutral(c, 28)

    private fun isDarkCardSlate(c: Int): Boolean = luminance(c) in 18..98 && isNeutral(c, 22)

    private fun isDarkInk(c: Int): Boolean = luminance(c) <= 135 && isNeutral(c, 36)

    private fun isLightText(c: Int): Boolean = luminance(c) >= 135 && isNeutral(c, 30)

    /**
     * Detects Roblox's dark-slate ErrorPrompt kick/disconnect modal ("Disconnected" + "Leave" / "Reconnect")
     * inside [windowBounds] (excluding top window title chrome) and returns screen tap coordinates for "Leave".
     */
    fun detectLeaveButton(frame: ScreenFrame?, windowBounds: RectBounds? = null): LeaveMatch? {
        if (frame == null || frame.width < 160 || frame.height < 100) return null
        if (frame.pixels.size < frame.width * frame.height) return null

        val rawWin = windowBounds ?: RectBounds(0, 0, frame.width, frame.height)
        val winLeft = rawWin.left.coerceIn(0, frame.width - 1)
        val winTop = rawWin.top.coerceIn(0, frame.height - 1)
        val winRight = rawWin.right.coerceIn(winLeft + 1, frame.width)
        val winBottom = rawWin.bottom.coerceIn(winTop + 1, frame.height)
        val winH = winBottom - winTop
        val topInset = if (winH < frame.height * 0.85) (winH * 0.14).toInt().coerceIn(28, 64) else 20

        val canvas = RectBounds(
            left = (winLeft + 4).coerceAtMost(winRight - 1),
            top = (winTop + topInset).coerceAtMost(winBottom - 1),
            right = (winRight - 4).coerceAtLeast(winLeft + 1),
            bottom = (winBottom - 4).coerceAtLeast(winTop + 1),
        )
        val cw = canvas.right - canvas.left
        val ch = canvas.bottom - canvas.top
        if (cw < 160 || ch < 100) return null

        val cx = (canvas.left + canvas.right) / 2
        val scanYStart = canvas.top + (ch * 0.36).toInt()
        val scanYEnd = canvas.bottom - maxOf(8, (ch * 0.06).toInt())
        val minSingleW = (cw * 0.22).toInt()
        val maxSingleW = (cw * 0.82).toInt()
        val minPairW = (cw * 0.13).toInt()
        val maxPairW = (cw * 0.42).toInt()

        var y = scanYStart
        while (y <= scanYEnd) {
            val runs = findWhiteRuns(frame, y, canvas.left + 8, canvas.right - 8, minPairW)
            for ((segL, segR) in runs) {
                val segW = segR - segL + 1
                val segCx = (segL + segR) / 2
                val isCenteredSingle =
                    segW in minSingleW..maxSingleW &&
                        segL < cx - (cw * 0.08).toInt() &&
                        segR > cx + (cw * 0.08).toInt() &&
                        abs(segCx - cx) <= (cw * 0.12).toInt()
                val isRightPair =
                    segW in minPairW..maxPairW &&
                        segL >= cx - (cw * 0.08).toInt() &&
                        segR <= canvas.right - (cw * 0.06).toInt()

                if (!isCenteredSingle && !isRightPair) continue

                val btnVert = measureButtonBand(frame, y, segL, segR, canvas.bottom - 4, ch) ?: continue
                val (btnTop, btnBot) = btnVert
                val match = verifyModalForButton(
                    frame = frame,
                    canvas = canvas,
                    cw = cw,
                    ch = ch,
                    cx = cx,
                    btnLeft = segL,
                    btnRight = segR,
                    btnTop = btnTop,
                    btnBot = btnBot,
                    pairedRight = !isCenteredSingle,
                )
                if (match != null) return match
            }
            y += 2
        }
        return null
    }

    private fun findWhiteRuns(frame: ScreenFrame, y: Int, xMin: Int, xMax: Int, minWidth: Int): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        val offset = y * frame.width
        var x = xMin
        while (x <= xMax) {
            if (!isWhiteButtonBg(frame.pixels[offset + x])) {
                x++
                continue
            }
            val start = x
            var end = x
            var gap = 0
            x++
            while (x <= xMax) {
                if (isWhiteButtonBg(frame.pixels[offset + x])) {
                    end = x
                    gap = 0
                } else {
                    gap++
                    if (gap > 2) break
                }
                x++
            }
            if (end - start + 1 >= minWidth) {
                out.add(start to end)
            }
        }
        return out
    }

    private fun measureButtonBand(
        frame: ScreenFrame,
        startY: Int,
        segL: Int,
        segR: Int,
        yLimit: Int,
        ch: Int,
    ): Pair<Int, Int>? {
        val segW = segR - segL + 1
        val minH = maxOf(10, (ch * 0.06).toInt())
        val maxH = maxOf(22, (ch * 0.28).toInt())

        fun rowMatchesButton(row: Int): Pair<Boolean, Int> {
            val off = row * frame.width
            var whiteCnt = 0
            var inkCnt = 0
            var darkCardCnt = 0
            var samples = 0
            val step = maxOf(1, segW / 60)
            var x = segL
            while (x <= segR) {
                val c = frame.pixels[off + x]
                if (isWhiteButtonBg(c)) whiteCnt++
                else if (isDarkInk(c)) inkCnt++
                if (isDarkCardSlate(c)) darkCardCnt++
                samples++
                x += step
            }
            val ok = samples > 0 &&
                (whiteCnt + inkCnt) >= (samples * 0.72) &&
                whiteCnt >= (samples * 0.45) &&
                darkCardCnt < (samples * 0.65)
            return ok to inkCnt
        }

        var top = startY
        while (top > startY - maxH && top > 0) {
            if (!rowMatchesButton(top - 1).first) break
            top--
        }
        var bot = startY
        while (bot + 1 <= yLimit && bot - top + 1 < maxH) {
            if (!rowMatchesButton(bot + 1).first) break
            bot++
        }
        val h = bot - top + 1
        if (h !in minH..maxH) return null

        // Verify white fill & dark glyph text ("Leave" / "Reconnect") inside the button rectangle.
        var total = 0
        var white = 0
        var ink = 0
        val stepX = maxOf(1, segW / 50)
        val stepY = maxOf(1, h / 16)
        var py = top + 1
        while (py <= bot - 1) {
            val off = py * frame.width
            var px = segL + 2
            while (px <= segR - 2) {
                val c = frame.pixels[off + px]
                if (isWhiteButtonBg(c)) white++
                else if (isDarkInk(c)) ink++
                total++
                px += stepX
            }
            py += stepY
        }
        if (total == 0) return null
        val whiteRatio = white.toDouble() / total
        val inkRatio = ink.toDouble() / total
        if (whiteRatio < 0.55 || inkRatio < 0.01 || inkRatio > 0.45) return null
        return top to bot
    }

    private fun verifyModalForButton(
        frame: ScreenFrame,
        canvas: RectBounds,
        cw: Int,
        ch: Int,
        cx: Int,
        btnLeft: Int,
        btnRight: Int,
        btnTop: Int,
        btnBot: Int,
        pairedRight: Boolean,
    ): LeaveMatch? {
        val midBtnY = (btnTop + btnBot) / 2
        val maxSideSearch = canvas.right - 2
        var cardRight = btnRight + 4
        while (cardRight < maxSideSearch) {
            val c = frame.pixels[midBtnY * frame.width + cardRight]
            if (!isDarkCardSlate(c)) break
            cardRight++
        }
        cardRight = (cardRight - 1).coerceAtLeast(btnRight + 2)

        val cardLeft = if (!pairedRight) {
            var left = btnLeft - 4
            while (left > canvas.left + 2) {
                val c = frame.pixels[midBtnY * frame.width + left]
                if (!isDarkCardSlate(c)) break
                left--
            }
            (left + 1).coerceAtMost(btnLeft - 2)
        } else {
            val rightPad = cardRight - btnRight
            val btnW = btnRight - btnLeft
            val symmetricLeft = cx - (cardRight - cx)
            val fallbackLeft = (btnLeft - btnW - maxOf(12, rightPad * 2)).coerceAtLeast(canvas.left + 4)
            minOf(symmetricLeft, fallbackLeft).coerceAtLeast(canvas.left + 4)
        }

        val cardW = cardRight - cardLeft + 1
        if (cardW < (cw * 0.34).toInt() || cardW > (cw * 0.90).toInt()) return null
        val cardCx = (cardLeft + cardRight) / 2
        if (abs(cardCx - cx) > (cw * 0.12).toInt()) return null

        // Check dark card slate strip right below the button.
        val belowEnd = minOf(canvas.bottom, btnBot + maxOf(10, (ch * 0.10).toInt()))
        var belowCardBottom = btnBot
        for (ry in (btnBot + 2)..belowEnd) {
            val ratio = slateCoverage(frame, ry, cardLeft + 8, cardRight - 8)
            if (ratio >= 0.60) belowCardBottom = ry else if (ry > btnBot + 4) break
        }
        if (belowCardBottom <= btnBot + 1) return null

        // Trace upward through the card body to find cardTop.
        var cardTop = btnTop - 4
        val topLimit = canvas.top + 2
        while (cardTop > topLimit) {
            val ratio = slateCoverage(frame, cardTop - 1, cardLeft + 8, cardRight - 8)
            if (ratio < 0.45) break
            cardTop--
        }
        val cardH = belowCardBottom - cardTop + 1
        if (cardH < (ch * 0.22).toInt() || cardH > (ch * 0.85).toInt()) return null

        // Inspect body region above buttons: must be mostly dark neutral slate with visible light text.
        val bodyTop = cardTop + 4
        val bodyBot = btnTop - 4
        if (bodyBot - bodyTop < maxOf(18, (ch * 0.10).toInt())) return null
        var bodyTotal = 0
        var bodySlate = 0
        var bodyLightText = 0
        var bodyBright = 0
        val bxStep = maxOf(1, (cardW - 16) / 36)
        val byStep = maxOf(1, (bodyBot - bodyTop) / 24)
        var by = bodyTop
        while (by <= bodyBot) {
            val off = by * frame.width
            var bx = cardLeft + 8
            while (bx <= cardRight - 8) {
                val c = frame.pixels[off + bx]
                if (isDarkCardSlate(c)) bodySlate++
                if (isLightText(c)) bodyLightText++
                if (luminance(c) >= 135) bodyBright++
                bodyTotal++
                bx += bxStep
            }
            by += byStep
        }
        if (bodyTotal == 0) return null
        val slateRatio = bodySlate.toDouble() / bodyTotal
        val lightRatio = bodyLightText.toDouble() / bodyTotal
        val brightRatio = bodyBright.toDouble() / bodyTotal
        if (slateRatio < 0.52 || lightRatio < 0.006 || brightRatio > 0.40) return null

        // Verify the modal has distinct left/right outer boundary inside the canvas (not an edge-to-edge fill).
        val leftMarginW = cardLeft - canvas.left
        val rightMarginW = canvas.right - cardRight
        if (leftMarginW >= 8 && rightMarginW >= 8) {
            val midCardY = (cardTop + bodyBot) / 2
            val leftOutSlate = slateCoverage(frame, midCardY, canvas.left + 2, cardLeft - 4)
            val rightOutSlate = slateCoverage(frame, midCardY, cardRight + 4, canvas.right - 2)
            if (leftOutSlate > 0.90 && rightOutSlate > 0.90) return null
        }

        if (!pairedRight) {
            return LeaveMatch(
                tapX = (btnLeft + btnRight) / 2,
                tapY = midBtnY,
                cardBounds = RectBounds(cardLeft, cardTop, cardRight, belowCardBottom),
                buttonCount = 1,
                source = "visual",
            )
        }

        // For 2-button modal: verify left outlined "Leave" button has light text centered inside left half.
        val leftBtnL = (cardLeft + maxOf(6, (cardW * 0.05).toInt())).coerceAtMost(cardCx - 12)
        val leftBtnR = (cardCx - maxOf(4, (cardW * 0.03).toInt())).coerceAtLeast(leftBtnL + 10)
        var lTotal = 0
        var lText = 0
        var lDark = 0
        for (py in (btnTop + 2)..(btnBot - 2) step maxOf(1, (btnBot - btnTop) / 12)) {
            val off = py * frame.width
            for (px in leftBtnL..leftBtnR step maxOf(1, (leftBtnR - leftBtnL) / 24)) {
                val c = frame.pixels[off + px]
                if (isLightText(c)) lText++ else if (isDarkCardSlate(c)) lDark++
                lTotal++
            }
        }
        if (lTotal == 0) return null
        val leftTextRatio = lText.toDouble() / lTotal
        val leftDarkRatio = lDark.toDouble() / lTotal
        if (leftDarkRatio < 0.35 || leftTextRatio < 0.01) return null

        return LeaveMatch(
            tapX = (leftBtnL + leftBtnR) / 2,
            tapY = midBtnY,
            cardBounds = RectBounds(cardLeft, cardTop, cardRight, belowCardBottom),
            buttonCount = 2,
            source = "visual",
        )
    }

    private fun slateCoverage(frame: ScreenFrame, y: Int, x0: Int, x1: Int): Double {
        if (y !in 0 until frame.height || x1 <= x0) return 0.0
        val off = y * frame.width
        val step = maxOf(1, (x1 - x0) / 28)
        var hit = 0
        var total = 0
        var x = x0
        while (x <= x1) {
            if (isDarkCardSlate(frame.pixels[off + x])) hit++
            total++
            x += step
        }
        return if (total == 0) 0.0 else hit.toDouble() / total
    }
}
