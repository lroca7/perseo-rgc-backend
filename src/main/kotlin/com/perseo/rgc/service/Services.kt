package com.perseo.rgc.service

import com.perseo.rgc.dto.*
import com.perseo.rgc.model.*
import com.perseo.rgc.repository.*
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.YearMonth

@Service
class SaldosService(
    private val socioRepository: SocioRepository,
    private val aporteRepository: AporteRepository,
    private val prestamoRepository: PrestamoRepository,
    private val gastoRepository: GastoRepository,
    private val utilidadRepository: UtilidadRepository,
    private val amortizacionService: AmortizacionService,
) {
    fun totalAportesSocio(socioId: String): Long =
        aporteRepository.findBySocioId(socioId).sumOf { it.monto }

    fun totalAportesGeneral(): Long =
        socioRepository.findAll().sumOf { totalAportesSocio(it.id!!) }

    fun totalPrestamoPendienteSocio(socioId: String): Long =
        prestamoRepository.findBySocioId(socioId)
            .filter { it.estado != "pagado" }
            .sumOf { amortizacionService.saldoPendiente(it) }

    fun totalPrestamosPendientesGeneral(): Long =
        socioRepository.findAll().sumOf { totalPrestamoPendienteSocio(it.id!!) }

    fun utilidadAsignadaSocio(socioId: String): Long =
        utilidadRepository.findAll().sumOf { u ->
            u.distribucion.find { it.socioId == socioId }?.monto ?: 0L
        }

    fun totalUtilidadLiquidada(): Long = utilidadRepository.findAll().sumOf { it.utilidadNeta }

    fun interesesCobradosTotal(): Long =
        prestamoRepository.findAll().sumOf { p -> p.cuotas.filter { it.pagado }.sumOf { it.interes } }

    fun saldoBancos(): Long {
        val aportes = totalAportesGeneral()
        val prestamos = prestamoRepository.findAll()
        val capitalPrestado = prestamos.sumOf { it.monto }
        val capitalRecuperado = capitalPrestado - totalPrestamosPendientesGeneral()
        val intereses = interesesCobradosTotal()
        val gastos = gastoRepository.findAll().sumOf { it.monto }
        return aportes - capitalPrestado + capitalRecuperado + intereses - gastos
    }

    fun utilidadPeriodo(periodo: String): Triple<Long, Long, Long> {
        val prestamos = prestamoRepository.findAll()
        val intereses = prestamos.sumOf { p ->
            p.cuotas.filter { it.pagado && it.fechaPago != null && it.fechaPago.toString().substring(0, 7) == periodo }
                .sumOf { it.interes }
        }
        val gastos = gastoRepository.findByPeriodo(periodo).sumOf { it.monto }
        return Triple(intereses, gastos, intereses - gastos)
    }

    fun proponerDistribucion(periodo: String): UtilidadPeriodoResponse {
        val (intereses, gastos, neta) = utilidadPeriodo(periodo)
        val totalAportes = totalAportesGeneral()
        val distribucion = socioRepository.findAll().map { s ->
            val base = totalAportesSocio(s.id!!)
            val parte = if (totalAportes > 0) (base.toDouble() / totalAportes) * neta else 0.0
            DistribucionItem(s.id, base, Math.round(parte))
        }
        val yaLiquidado = utilidadRepository.findByPeriodo(periodo) != null
        return UtilidadPeriodoResponse(periodo, intereses, gastos, neta, distribucion, yaLiquidado)
    }

    fun resumen(): ResumenResponse {
        val socios = socioRepository.findAll()
        val periodoActual = YearMonth.now().toString()
        val (interesesMes, _, _) = utilidadPeriodo(periodoActual)
        val gastosTotales = gastoRepository.findAll().sumOf { it.monto }
        val utilidadSinLiquidar = interesesCobradosTotal() - gastosTotales - totalUtilidadLiquidada()
        val saldos = socios.map { s ->
            val ap = totalAportesSocio(s.id!!)
            val pr = totalPrestamoPendienteSocio(s.id)
            val ut = utilidadAsignadaSocio(s.id)
            SocioSaldoResponse(s.id, s.nombre, s.activo, ap, pr, ut, ap - pr + ut)
        }
        return ResumenResponse(
            bancos = saldoBancos(),
            aportesTotales = totalAportesGeneral(),
            prestamosPendientes = totalPrestamosPendientesGeneral(),
            utilidadSinLiquidar = utilidadSinLiquidar,
            interesesMesActual = interesesMes,
            saldosPorSocio = saldos,
        )
    }

    fun proyectar(meses: Int, aporteMensualExtra: Long): List<ProyeccionMesResponse> {
        val prestamos = prestamoRepository.findAll()
        val sociosActivos = socioRepository.findAll().count { it.activo }
        var acumAportes = totalAportesGeneral()
        var acumUtilidad = totalUtilidadLiquidada()
        val resultado = mutableListOf<ProyeccionMesResponse>()
        var fecha = LocalDate.now()
        for (k in 1..meses) {
            fecha = fecha.plusMonths(1)
            val periodo = YearMonth.from(fecha).toString()
            val interesesMes = prestamos.sumOf { p ->
                p.cuotas.filter { !it.pagado && YearMonth.from(it.fechaProgramada).toString() == periodo }
                    .sumOf { it.interes }
            }
            val utilidadMes = interesesMes
            acumUtilidad += utilidadMes
            acumAportes += aporteMensualExtra * sociosActivos
            resultado.add(ProyeccionMesResponse(periodo, interesesMes, utilidadMes, acumUtilidad, acumAportes))
        }
        return resultado
    }
}

@Service
class SeedService(
    private val socioRepository: SocioRepository,
    private val aporteRepository: AporteRepository,
    private val prestamoRepository: PrestamoRepository,
    private val gastoRepository: GastoRepository,
    private val configRepository: ConfigRepository,
) {
    /** Datos de partida tomados de la hoja "SEPTIEMBRE 26" al 16-sep-2026. */
    fun seedIfEmpty(tasaDefault: Double) {
        if (socioRepository.count() > 0) return

        data class Seed(val id: String, val nombre: String, val aportes: Long, val prestamo: Long)
        val seeds = listOf(
            Seed("hector_de_arco", "Hector Rodriguez De Arco", 9037711, 1000000),
            Seed("ledy", "Ledy Rodriguez Geney", 5180448, 7355607),
            Seed("viviana", "Viviana Rodriguez Geney", 5180705, 5408616),
            Seed("lizeth", "Lizeth Rodriguez Cabrales", 5180448, 4569949),
            Seed("hector_junior", "Hector Rodriguez Cabrales", 5180448, 6946419),
            Seed("martha", "Martha Gonzalez", 2200000, 3857815),
            Seed("hernan", "Hernan Miranda", 1800000, 3086252),
            Seed("fabian", "Fabian Poveda", 3000000, 6000000),
            Seed("luz_enith", "Luz Enith Cabrales", 400000, 0),
            Seed("vilma", "Vilma Geney", 400000, 0),
        )
        val periodo = "2026-09"
        for (s in seeds) {
            socioRepository.save(Socio(id = s.id, nombre = s.nombre, activo = true, fechaIngreso = LocalDate.of(2026, 1, 1)))
            aporteRepository.save(
                Aporte(
                    socioId = s.id, periodo = periodo, fecha = LocalDate.of(2026, 9, 16),
                    monto = s.aportes, nota = "Saldo inicial migrado desde Excel (acumulado a la fecha)"
                )
            )
            if (s.prestamo > 0) {
                prestamoRepository.save(
                    Prestamo(
                        socioId = s.id, monto = s.prestamo, tasa = tasaDefault, numCuotas = 0,
                        fechaInicio = LocalDate.of(2026, 9, 16), estado = "activo", cuotas = mutableListOf(),
                        nota = "Saldo migrado desde Excel sin tabla de amortización detallada. Genera el plan de pago desde ahora."
                    )
                )
            }
        }
        gastoRepository.save(
            Gasto(
                concepto = "Atención asociados + 4x1000 (acumulado Ene-Jul 2026)",
                periodo = "2026-07", fecha = LocalDate.of(2026, 7, 11), monto = 585660
            )
        )
        if (configRepository.count() == 0L) {
            configRepository.save(ConfigGlobal(tasaDefault = tasaDefault))
        }
    }
}
