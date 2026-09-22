package com.perseo.rgc.service

import com.perseo.rgc.model.Cuota
import com.perseo.rgc.model.Prestamo
import org.springframework.stereotype.Service
import java.time.LocalDate
import kotlin.math.pow
import kotlin.math.roundToLong

@Service
class AmortizacionService {

    /**
     * Genera la tabla de amortización por el método francés (cuota fija),
     * igual criterio que se usaba en la hoja de Excel original.
     */
    fun calcular(monto: Long, tasaMensual: Double, numCuotas: Int, fechaInicio: LocalDate): MutableList<Cuota> {
        val cuotas = mutableListOf<Cuota>()
        var saldo = monto.toDouble()
        val i = tasaMensual
        val n = numCuotas
        val cuotaFija = if (i == 0.0) monto.toDouble() / n else monto * i / (1 - (1 + i).pow(-n))
        var fecha = fechaInicio
        for (k in 1..n) {
            fecha = fecha.plusMonths(1)
            val interes = saldo * i
            var capital = cuotaFija - interes
            if (k == n) capital = saldo // ajuste de redondeo en la última cuota
            saldo = maxOf(0.0, saldo - capital)
            cuotas.add(
                Cuota(
                    numero = k,
                    fechaProgramada = fecha,
                    cuota = cuotaFija.roundToLong(),
                    interes = interes.roundToLong(),
                    capital = capital.roundToLong(),
                    saldo = saldo.roundToLong(),
                )
            )
        }
        return cuotas
    }

    fun saldoPendiente(prestamo: Prestamo): Long {
        if (prestamo.cuotas.isEmpty()) return prestamo.monto
        val idx = prestamo.cuotas.indexOfFirst { !it.pagado }
        if (idx == -1) return 0L
        return if (idx == 0) prestamo.monto else prestamo.cuotas[idx - 1].saldo
    }
}
