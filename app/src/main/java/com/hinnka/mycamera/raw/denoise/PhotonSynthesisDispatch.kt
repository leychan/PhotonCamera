package com.hinnka.mycamera.raw.denoise

/** 单位为工作组；区域互不重叠，合起来覆盖整层。 */
internal data class PhotonSynthesisRegion(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val interior: Boolean,
)

internal object PhotonSynthesisDispatch {
    // pilot 半径 2，结构张量半径 1，中心差分半径 1，共需四像素邻域。
    const val BORDER = 4

    fun regions(width: Int, height: Int, tileHeight: Int, splitInterior: Boolean): List<PhotonSynthesisRegion> {
        require(width > 0 && height > 0)
        require(tileHeight == 8 || tileHeight == 16)
        val tileWidth = PhotonDenoiseShaders.SYNTHESIS_TILE_WIDTH
        val columns = (width + tileWidth - 1) / tileWidth
        val rows = (height + tileHeight - 1) / tileHeight
        val x0 = (BORDER + tileWidth - 1) / tileWidth
        val y0 = (BORDER + tileHeight - 1) / tileHeight
        val x1 = (width - BORDER) / tileWidth
        val y1 = (height - BORDER) / tileHeight
        if (!splitInterior || x1 <= x0 || y1 <= y0) {
            return listOf(PhotonSynthesisRegion(0, 0, columns, rows, false))
        }
        return buildList {
            add(PhotonSynthesisRegion(x0, y0, x1 - x0, y1 - y0, true))
            fun border(x: Int, y: Int, w: Int, h: Int) {
                if (w > 0 && h > 0) add(PhotonSynthesisRegion(x, y, w, h, false))
            }
            border(0, 0, columns, y0)
            border(0, y1, columns, rows - y1)
            border(0, y0, x0, y1 - y0)
            border(x1, y0, columns - x1, y1 - y0)
        }
    }
}
