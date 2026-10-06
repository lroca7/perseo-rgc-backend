package com.perseo.rgc.service

import com.perseo.rgc.model.Cuota
import com.perseo.rgc.model.Prestamo
import org.springframework.stereotype.Service
import java.time.LocalDate
import kotlin.math.pow
import kotlin.math.round

/** Redondea a 2 decimales (centavos), evitando arrastrar errores de punto flotante. */
fun redondear2(x: Double): Double = round(x * 100.0) / 100.0

@Service
class AmortizacionService {

    /**
     * Genera la tabla de amortización por el método francés (cuota fija),
     * igual criterio que se usaba en la hoja de Excel original.
     *
     * Los montos se conservan con hasta 2 decimales (no se truncan a peso entero),
     * para que coincidan con lo que muestra el Excel.
     *
     * Si [aplicarGraciaDiciembre] es true (por defecto), cualquier mes que caiga en
     * diciembre se inserta como fila de "mes de gracia": no se cobra cuota ni interés
     * ese mes y el saldo no cambia. Los meses de gracia NO cuentan dentro de [numCuotas]:
     * un préstamo a 18 cuotas siempre tendrá 18 cuotas que sí cobran, aunque el calendario
     * total se alargue por los diciembres de por medio.
     */
    fun calcular(
        monto: Double,
        tasaMensual: Double,
        numCuotas: Int,
        fechaInicio: LocalDate,
        aplicarGraciaDiciembre: Boolean = true,
    ): MutableList<Cuota> {
        val cuotas = mutableListOf<Cuota>()
        var saldo = monto
        val i = tasaMensual
        val n = numCuotas
        val cuotaFija = if (i == 0.0) monto / n else monto * i / (1 - (1 + i).pow(-n))
        var fecha = fechaInicio
        var numero = 0
        var cuotasReales = 0
        while (cuotasReales < n) {
            fecha = fecha.plusMonths(1)
            numero++
            if (aplicarGraciaDiciembre && fecha.monthValue == 12) {
                cuotas.add(
                    Cuota(
                        numero = numero, fechaProgramada = fecha, cuota = 0.0, interes = 0.0, capital = 0.0,
                        saldo = redondear2(saldo), pagado = true, fechaPago = null, montoPagado = 0.0, esGracia = true,
                    )
                )
                continue
            }
            cuotasReales++
            val interes = saldo * i
            var capital = cuotaFija - interes
            if (cuotasReales == n) capital = saldo // ajuste de redondeo en la última cuota real
            saldo = maxOf(0.0, saldo - capital)
            cuotas.add(
                Cuota(
                    numero = numero, fechaProgramada = fecha,
                    cuota = redondear2(cuotaFija), interes = redondear2(interes), capital = redondear2(capital),
                    saldo = redondear2(saldo),
                )
            )
        }
        return cuotas
    }

    fun saldoPendiente(prestamo: Prestamo): Double {
        if (prestamo.cuotas.isEmpty()) return prestamo.monto
        // Las cuotas de gracia ya vienen marcadas pagado=true, así que buscamos
        // directamente la primera cuota real sin pagar.
        val idxReal = prestamo.cuotas.indexOfFirst { !it.pagado }
        if (idxReal == -1) return 0.0
        for (k in idxReal - 1 downTo 0) {
            if (!prestamo.cuotas[k].esGracia) return prestamo.cuotas[k].saldo
        }
        return prestamo.monto
    }

    /** true si el préstamo ya tiene al menos una cuota real (no de gracia) pagada. */
    fun tienePagosReales(prestamo: Prestamo): Boolean =
        prestamo.cuotas.any { it.pagado && !it.esGracia }
}