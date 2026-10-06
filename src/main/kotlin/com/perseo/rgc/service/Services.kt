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
    private val configRepository: ConfigRepository,
    private val amortizacionService: AmortizacionService,
) {
    /**
     * Todo lo que un cálculo de saldos/resumen necesita, traído en una sola tanda
     * de consultas (una por colección) y agrupado en memoria por socio.
     * Evita el patrón N+1 de volver a consultar Aportes/Préstamos/Utilidades
     * por cada socio dentro de un loop.
     */
    private data class Snapshot(
        val socios: List<Socio>,
        val aportesPorSocio: Map<String, List<Aporte>>,
        val prestamosPorSocio: Map<String, List<Prestamo>>,
        val gastos: List<Gasto>,
        val utilidades: List<Utilidad>,
        val ajusteBancos: Double,
    )

    private fun cargarSnapshot(): Snapshot {
        val socios = socioRepository.findAll()
        val aportes = aporteRepository.findAll()
        val prestamos = prestamoRepository.findAll()
        val gastos = gastoRepository.findAll()
        val utilidades = utilidadRepository.findAll()
        val config = configRepository.findById("global").orElse(ConfigGlobal())
        return Snapshot(
            socios = socios,
            aportesPorSocio = aportes.groupBy { it.socioId },
            prestamosPorSocio = prestamos.groupBy { it.socioId },
            gastos = gastos,
            utilidades = utilidades,
            ajusteBancos = config.ajusteBancos,
        )
    }

    private fun totalAportesSocio(snap: Snapshot, socioId: String): Double =
        snap.aportesPorSocio[socioId]?.sumOf { it.monto } ?: 0.0

    private fun totalAportesGeneral(snap: Snapshot): Double =
        snap.aportesPorSocio.values.sumOf { lista -> lista.sumOf { it.monto } }

    private fun totalPrestamoPendienteSocio(snap: Snapshot, socioId: String): Double =
        (snap.prestamosPorSocio[socioId] ?: emptyList())
            .filter { it.estado != "pagado" }
            .sumOf { amortizacionService.saldoPendiente(it) }

    private fun totalPrestamosPendientesGeneral(snap: Snapshot): Double =
        snap.socios.sumOf { totalPrestamoPendienteSocio(snap, it.id!!) }

    private fun utilidadAsignadaSocio(snap: Snapshot, socioId: String): Double =
        snap.utilidades.sumOf { u -> u.distribucion.find { it.socioId == socioId }?.monto ?: 0.0 }

    private fun totalUtilidadLiquidada(snap: Snapshot): Double = snap.utilidades.sumOf { it.utilidadNeta }

    private fun interesesCobradosTotal(snap: Snapshot): Double =
        snap.prestamosPorSocio.values.sumOf { lista ->
            lista.sumOf { p -> p.cuotas.filter { it.pagado }.sumOf { it.interes } }
        }

    private fun saldoBancos(snap: Snapshot): Double {
        val aportes = totalAportesGeneral(snap)
        val capitalPrestado = snap.prestamosPorSocio.values.sumOf { lista -> lista.sumOf { it.monto } }
        val capitalRecuperado = capitalPrestado - totalPrestamosPendientesGeneral(snap)
        val intereses = interesesCobradosTotal(snap)
        val gastos = snap.gastos.sumOf { it.monto }
        return redondear2(aportes - capitalPrestado + capitalRecuperado + intereses - gastos + snap.ajusteBancos)
    }

    private fun utilidadPeriodo(snap: Snapshot, periodo: String): Triple<Double, Double, Double> {
        val intereses = snap.prestamosPorSocio.values.sumOf { lista ->
            lista.sumOf { p ->
                p.cuotas.filter { it.pagado && it.fechaPago != null && it.fechaPago.toString().substring(0, 7) == periodo }
                    .sumOf { it.interes }
            }
        }
        val gastos = snap.gastos.filter { it.periodo == periodo }.sumOf { it.monto }
        return Triple(redondear2(intereses), redondear2(gastos), redondear2(intereses - gastos))
    }

    fun proponerDistribucion(periodo: String): UtilidadPeriodoResponse {
        val snap = cargarSnapshot()
        val (intereses, gastos, neta) = utilidadPeriodo(snap, periodo)
        val totalAportes = totalAportesGeneral(snap)
        val distribucion = snap.socios.map { s ->
            val base = totalAportesSocio(snap, s.id!!)
            val parte = if (totalAportes > 0) (base / totalAportes) * neta else 0.0
            DistribucionItem(s.id, redondear2(base), redondear2(parte))
        }
        val yaLiquidado = utilidadRepository.findByPeriodo(periodo) != null
        return UtilidadPeriodoResponse(periodo, intereses, gastos, neta, distribucion, yaLiquidado)
    }

    fun resumen(): ResumenResponse {
        val snap = cargarSnapshot()
        val periodoActual = YearMonth.now().toString()
        val (interesesMes, _, _) = utilidadPeriodo(snap, periodoActual)
        val gastosTotales = snap.gastos.sumOf { it.monto }
        val utilidadSinLiquidar = redondear2(interesesCobradosTotal(snap) - gastosTotales - totalUtilidadLiquidada(snap))
        val saldos = snap.socios.map { s ->
            val ap = redondear2(totalAportesSocio(snap, s.id!!))
            val pr = redondear2(totalPrestamoPendienteSocio(snap, s.id))
            val ut = redondear2(utilidadAsignadaSocio(snap, s.id))
            SocioSaldoResponse(s.id, s.nombre, s.activo, ap, pr, ut, redondear2(ap - pr + ut))
        }
        return ResumenResponse(
            bancos = saldoBancos(snap),
            aportesTotales = redondear2(totalAportesGeneral(snap)),
            prestamosPendientes = redondear2(totalPrestamosPendientesGeneral(snap)),
            utilidadSinLiquidar = utilidadSinLiquidar,
            interesesMesActual = interesesMes,
            saldosPorSocio = saldos,
        )
    }

    fun proyectar(meses: Int, aporteMensualExtra: Double): List<ProyeccionMesResponse> {
        val snap = cargarSnapshot()
        val prestamos = snap.prestamosPorSocio.values.flatten()
        val sociosActivos = snap.socios.count { it.activo }
        var acumAportes = totalAportesGeneral(snap)
        var acumUtilidad = totalUtilidadLiquidada(snap)
        val resultado = mutableListOf<ProyeccionMesResponse>()
        var fecha = LocalDate.now()
        for (k in 1..meses) {
            fecha = fecha.plusMonths(1)
            val periodo = YearMonth.from(fecha).toString()
            val interesesMes = prestamos.sumOf { p ->
                p.cuotas.filter { !it.pagado && YearMonth.from(it.fechaProgramada).toString() == periodo }
                    .sumOf { it.interes }
            }
            acumUtilidad += interesesMes
            acumAportes += aporteMensualExtra * sociosActivos
            resultado.add(
                ProyeccionMesResponse(
                    periodo, redondear2(interesesMes), redondear2(interesesMes),
                    redondear2(acumUtilidad), redondear2(acumAportes),
                )
            )
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

        data class Seed(val id: String, val nombre: String, val aportes: Double, val prestamo: Double)
        val seeds = listOf(
            Seed("hector_de_arco", "Hector Rodriguez De Arco", 9037711.0, 1000000.0),
            Seed("ledy", "Ledy Rodriguez Geney", 5180448.0, 7355607.0),
            Seed("viviana", "Viviana Rodriguez Geney", 5180705.0, 5408616.0),
            Seed("lizeth", "Lizeth Rodriguez Cabrales", 5180448.0, 4569949.0),
            Seed("hector_junior", "Hector Rodriguez Cabrales", 5180448.0, 6946419.0),
            Seed("martha", "Martha Gonzalez", 2200000.0, 3857815.0),
            Seed("hernan", "Hernan Miranda", 1800000.0, 3086252.0),
            Seed("fabian", "Fabian Poveda", 3000000.0, 6000000.0),
            Seed("luz_enith", "Luz Enith Cabrales", 400000.0, 0.0),
            Seed("vilma", "Vilma Geney", 400000.0, 0.0),
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
                periodo = "2026-07", fecha = LocalDate.of(2026, 7, 11), monto = 585660.0
            )
        )
        if (configRepository.count() == 0L) {
            configRepository.save(ConfigGlobal(tasaDefault = tasaDefault))
        }
    }
}