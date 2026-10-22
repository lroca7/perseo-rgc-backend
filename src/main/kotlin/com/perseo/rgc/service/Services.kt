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
    companion object {
        /**
         * Meses extra de horizonte después del mes del último turno. Da espacio para que
         * un turno se retrace lo necesario y para que los préstamos nuevos simulados
         * alcancen a devolver su capital al fondo.
         */
        const val HORIZONTE_MESES_EXTRA = 30
    }
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
        val ajusteInteresesHistoricos: Double,
        val tasaDefault: Double,
        val aporteMensualSocio: Double,
        val usarUltimoAporteReal: Boolean,
        val numCuotasSimulacion: Int,
        val prestamosParciales: Boolean,
    )

    private fun cargarSnapshot(): Snapshot {
        val socios = socioRepository.findAll()
        // Todo se ordena por id: Mongo no garantiza el orden de findAll(), y sumar en
        // órdenes distintos cambia el último centavo. Con el orden fijo, la simulación
        // da siempre el mismo resultado (y el reporte no cambia entre recargas).
        val aportes = aporteRepository.findAll().sortedBy { it.id }
        val prestamos = prestamoRepository.findAll().sortedBy { it.id }
        val gastos = gastoRepository.findAll().sortedBy { it.id }
        val utilidades = utilidadRepository.findAll().sortedBy { it.id }
        val config = configRepository.findById("global").orElse(ConfigGlobal())
        return Snapshot(
            socios = socios,
            aportesPorSocio = aportes.groupBy { it.socioId },
            prestamosPorSocio = prestamos.groupBy { it.socioId },
            gastos = gastos,
            utilidades = utilidades,
            ajusteInteresesHistoricos = config.ajusteInteresesHistoricos,
            tasaDefault = config.tasaDefault,
            aporteMensualSocio = config.aporteMensualSocio,
            usarUltimoAporteReal = config.usarUltimoAporteReal,
            numCuotasSimulacion = config.numCuotasSimulacion,
            prestamosParciales = config.prestamosParciales,
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

    /**
     * Utilidad ganada y todavía NO repartida: el interés cobrado a los socios menos lo
     * ya liquidado. Es dinero que está dentro de la caja pero que el fondo le debe a
     * sus socios, por eso la simulación de capacidad lo aparta antes de prestar.
     */
    private fun calcularUtilidadSinLiquidar(snap: Snapshot): Double = redondear2(
        interesesCobradosTotal(snap) - snap.gastos.sumOf { it.monto } -
                totalUtilidadLiquidada(snap) + snap.ajusteInteresesHistoricos
    )

    /**
     * Saldo de bancos (caja del fondo).
     *
     * Las utilidades ya liquidadas salen del fondo (el dinero se les paga a los socios),
     * así que se restan. Para no descontar dos veces los gastos de un período ya
     * liquidado (una vez acá y otra dentro de la utilidad neta, que ya los descuenta)
     * se suman de vuelta los gastos de esos períodos.
     */
    private fun saldoBancos(snap: Snapshot): Double {
        val aportes = totalAportesGeneral(snap)
        val capitalPrestado = snap.prestamosPorSocio.values.sumOf { lista -> lista.sumOf { it.monto } }
        val capitalRecuperado = capitalPrestado - totalPrestamosPendientesGeneral(snap)
        val intereses = interesesCobradosTotal(snap)
        val gastos = snap.gastos.sumOf { it.monto }
        val utilidadLiquidadas = totalUtilidadLiquidada(snap)
        val periodosLiquidados = snap.utilidades.map { it.periodo }.toSet()
        val gastosYaLiquidados = snap.gastos.filter { it.periodo in periodosLiquidados }.sumOf { it.monto }
        return redondear2(
            aportes - capitalPrestado + capitalRecuperado + intereses - gastos +
                    snap.ajusteInteresesHistoricos - utilidadLiquidadas + gastosYaLiquidados
        )
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
        val utilidadSinLiquidar = calcularUtilidadSinLiquidar(snap)
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
        // Arranca desde la "utilidad sin liquidar" actual (intereses reales ya cobrados
        // − gastos − lo ya liquidado + el ajuste de intereses históricos), igual que el
        // Resumen — de lo contrario, préstamos migrados sin tabla "desaparecen" de la
        // proyección en vez de contar como punto de partida.
        var acumUtilidad = interesesCobradosTotal(snap) - snap.gastos.sumOf { it.monto } -
                totalUtilidadLiquidada(snap) + snap.ajusteInteresesHistoricos
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

    /**
     * Simula mes a mes la capacidad de préstamo de cada socio siguiendo el orden de
     * turnos fijo (a partir de enero 2027, un socio por mes) y determina en qué mes
     * alcanza el fondo para el monto disponible de cada uno.
     *
     * Reglas (documentadas en `docs/capacidad-prestamos.md`):
     * - **Ancla**: último período con aportes registrados. Todo se proyecta desde ahí.
     * - **Aportes proyectados** = aportes acumulados + aporte mensual × nº de meses
     *   NO-diciembre entre el ancla y el mes en cuestión (el mes del turno va incluido).
     * - **Diciembre es mes de gracia**: no se cobran aportes, ni cuotas, ni utilidad.
     * - **Capacidad máxima** = 2 × aportes proyectados.
     * - **Disponible** = capacidad − saldo del préstamo activo **proyectado** al mes
     *   (el capital que el socio ya pagó en cuotas anteriores se descuenta).
     * - **Fondo (caja)** = bancos de hoy, avanzando mes a mes con
     *   aportes + cobros de cuotas − desembolsos − utilidades repartidas.
     * - **Utilidad**: en junio y diciembre se reparte la acumulada (incluida la que ya
     *   estaba cobrada y pendiente). Sale de la caja en efectivo.
     * - **Regla de liquidez**: solo se otorga un préstamo si la *caja disponible*
     *   (caja − utilidad acumulada sin repartir) lo cubre. Así el fondo no presta lo
     *   que le debe a sus propios socios y la caja nunca queda negativa.
     * - **Turnos encadenados**: si un socio no alcanza, los siguientes esperan turno.
     * - **Liquidación con cobertura**: la utilidad de junio/diciembre solo se reparte
     *   si la caja alcanza para pagarla; si no, se pospone y sigue acumulada.
     *
     * Es una simulación: no escribe nada en la base de datos.
     */
    fun capacidadPrestamos(): List<CapacidadPrestamoItem> = simularCapacidad().items

    /**
     * Proyección de la caja del fondo mes a mes (misma simulación que
     * [capacidadPrestamos], así que las dos vistas nunca se contradicen).
     */
    fun proyeccionFondo(): FondoProyeccionResponse {
        val sim = simularCapacidad()
        return FondoProyeccionResponse(
            anclaPeriodo = sim.ancla.toString(),
            meses = sim.mesesFondo,
            liquidaciones = sim.liquidaciones,
            proximaLiquidacion = sim.liquidaciones.firstOrNull(),
        )
    }

    /** Resultado interno de la simulación: los socios en sus turnos y la trayectoria del fondo. */
    private data class SimulacionCapacidad(
        val items: List<CapacidadPrestamoItem>,
        val mesesFondo: List<FondoMesItem>,
        val liquidaciones: List<LiquidacionItem>,
        val ancla: YearMonth,
    )

    private fun simularCapacidad(): SimulacionCapacidad {
        val snap = cargarSnapshot()
        val aporteMensual = snap.aporteMensualSocio

        /** Aporte mensual proyectado de un socio: el global, o su último aporte real. */
        fun aporteMensualDe(socioId: String): Double {
            if (!snap.usarUltimoAporteReal) return aporteMensual
            val ultimo = snap.aportesPorSocio[socioId]?.maxByOrNull { it.fecha }?.monto
            return ultimo ?: aporteMensual
        }

        val aporteTotalMensual: Double = if (snap.usarUltimoAporteReal)
            snap.socios.filter { it.activo }.sumOf { aporteMensualDe(it.id!!) }
        else aporteMensual * snap.socios.count { it.activo }

        // --- Ancla: último período con aportes registrados ---
        val ancla: YearMonth = snap.aportesPorSocio.values.flatten()
            .map { it.periodo }.maxOrNull()
            ?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
            ?: YearMonth.now()
        val mesInicial = ancla.plusMonths(1)

        // --- Cuotas futuras (todavía no pagadas) de cada socio ---
        val cuotasPorSocio: Map<String, List<CuotaFutura>> = snap.prestamosPorSocio.mapValues { (_, prestamos) ->
            prestamos.filter { it.estado != "pagado" }.flatMap { p ->
                p.cuotas.filter { !it.pagado }.map { c ->
                    CuotaFutura(
                        periodo = YearMonth.parse(c.fechaProgramada.toString().substring(0, 7)),
                        cuota = c.cuota,
                        interes = c.interes,
                        capital = c.capital,
                    )
                }
            }
        }

        /** Capital que el socio ya pagó en cuotas con fecha anterior o igual a [mes]. */
        fun capitalHasta(socioId: String, mes: YearMonth): Double =
            cuotasPorSocio[socioId].orEmpty().filter { !it.periodo.isAfter(mes) }.sumOf { it.capital }

        /** Nº de meses con aporte entre [desde] (excluido) y [hasta] (incluido), sin diciembre. */
        fun mesesConAporte(desde: YearMonth, hasta: YearMonth): Int {
            var n = 0
            var it = desde.plusMonths(1)
            while (!it.isAfter(hasta)) {
                if (it.monthValue != 12) n++
                it = it.plusMonths(1)
            }
            return n
        }

        /**
         * Cupo de un socio calculado para un mes concreto: aportes proyectados hasta ese
         * mes, capacidad (2×), saldo proyectado de su préstamo activo y el disponible
         * (capacidad − saldo). Es la fórmula única: la usan tanto la captura del mes de
         * turno como el loop de otorgamientos.
         */
        fun calcularCupo(socioId: String, mes: YearMonth): CupoSim {
            val meses = mesesConAporte(ancla, mes)
            val aportesProy = redondear2(
                totalAportesSocio(snap, socioId) + aporteMensualDe(socioId) * meses
            )
            val capacidad = redondear2(aportesProy * 2)
            val saldo = redondear2(totalPrestamoPendienteSocio(snap, socioId) - capitalHasta(socioId, mes))
            return CupoSim(
                meses = meses,
                aportesProyectados = aportesProy,
                capacidadMax = capacidad,
                saldoProyectado = saldo,
                disponible = redondear2(maxOf(0.0, capacidad - saldo)),
            )
        }

        // Orden de turnos definido por el usuario (a partir de enero 2027, un socio por mes).
        // Lucy y Vilma son socios honorarios: no realizan préstamos, no entran en el reporte.
        val ordenNombres = listOf("Hector Jose", "Viviana", "Lizeth", "Ledy", "Martha", "Hernan", "Fabian")
        val palabrasClave = mapOf(
            "Hector Jose" to listOf("hector", "de arco"),
            "Viviana" to listOf("viviana"),
            "Lizeth" to listOf("lizeth"),
            "Ledy" to listOf("ledy"),
            "Martha" to listOf("martha"),
            "Hernan" to listOf("hernan"),
            "Fabian" to listOf("fabian"),
        )

        val idsUsados = mutableSetOf<String>()
        val sociosOrdenados = mutableListOf<Socio>()
        for (nombreBuscado in ordenNombres) {
            val claves = palabrasClave[nombreBuscado]!!
            val socio = snap.socios.find { s ->
                s.id != null && s.id !in idsUsados &&
                    claves.all { palabra -> s.nombre.lowercase().contains(palabra.lowercase()) }
            }
            if (socio != null && socio.id != null) {
                idsUsados.add(socio.id!!)
                sociosOrdenados.add(socio)
            }
        }

        // --- Estado de la simulación ---
        var fondo = redondear2(saldoBancos(snap))
        var utilidadPendiente = redondear2(calcularUtilidadSinLiquidar(snap))
        val nuevasCuotas = mutableMapOf<YearMonth, CuotaSimulada>()
        val recogidoEn = mutableMapOf<String, YearMonth>()
        var ultimoMesOtorgado: YearMonth? = null

        val primerMesTurno = YearMonth.of(2027, 1)
        val turnos = sociosOrdenados.mapIndexed { i, socio ->
            TurnoSim(socio.id!!, socio.nombre, i + 1, primerMesTurno.plusMonths(i.toLong()))
        }
        val mesFin = turnos.lastOrNull()?.mesTurno?.plusMonths(HORIZONTE_MESES_EXTRA.toLong())
            ?: mesInicial.plusMonths(24)

        var mes = mesInicial
        val mesesFondo = mutableListOf<FondoMesItem>()
        val liquidaciones = mutableListOf<LiquidacionItem>()
        while (!mes.isAfter(mesFin)) {
            val fondoInicial = fondo

            // 1. Aportes mensuales recurrentes (diciembre es mes de gracia: no se cobran)
            val aportesMes = if (mes.monthValue == 12) 0.0 else aporteTotalMensual
            if (aportesMes > 0.0) fondo = redondear2(fondo + aportesMes)

            // 2. Cobros de cuotas del mes (capital + interés).
            //    Se redondea en cada suma para que el resultado no dependa del orden
            //    en que se recorren las cuotas (aritmética de 2 decimales exacta).
            var cobros = 0.0
            var interesMes = 0.0
            for ((socioId, cuotas) in cuotasPorSocio) {
                val rec = recogidoEn[socioId]
                for (c in cuotas) {
                    if (c.periodo != mes) continue
                    if (rec != null && c.periodo.isAfter(rec)) continue
                    cobros = redondear2(cobros + c.cuota)
                    interesMes = redondear2(interesMes + c.interes)
                }
            }
            for ((periodo, c) in nuevasCuotas) {
                if (periodo == mes) {
                    cobros = redondear2(cobros + c.cuota)
                    interesMes = redondear2(interesMes + c.interes)
                }
            }
            fondo = redondear2(fondo + cobros)
            utilidadPendiente = redondear2(utilidadPendiente + interesMes)

            // 3. Panorama del mes calendario de cada turno (para el reporte): cuánta caja
            //    había, cuánto le correspondía al socio y hasta dónde podía prestarle el
            //    fondo ahí (el "préstamo ideal").
            for (t in turnos) {
                if (t.mesTurno != mes || t.tieneDatosDeTurno) continue
                t.tieneDatosDeTurno = true
                t.fondoEnTurno = fondo
                val cajaDispTurno = redondear2(fondo - utilidadPendiente)
                val cupoTurno = calcularCupo(t.socioId, mes)
                t.cajaDisponibleEnTurno = cajaDispTurno
                t.disponibleEnTurno = cupoTurno.disponible
                t.aportesProyectadosEnTurno = cupoTurno.aportesProyectados
                t.capacidadMaxEnTurno = cupoTurno.capacidadMax
                t.saldoProyectadoEnTurno = cupoTurno.saldoProyectado
                t.prestamoIdealEnTurno = redondear2(minOf(cupoTurno.disponible, cajaDispTurno))
                t.brechaEnTurno = redondear2(cupoTurno.disponible - t.prestamoIdealEnTurno)
            }

            // 4. Turnos: encadenados y de uno en uno por mes
            var desembolsosDelMes = 0.0
            for ((indice, t) in turnos.withIndex()) {
                if (t.estado != "pendiente") continue
                if (t.mesTurno.isAfter(mes)) continue
                // Encadenado: si el turno anterior sigue pendiente, este espera su lugar.
                if (indice > 0 && turnos[indice - 1].estado == "pendiente") break
                // Un solo préstamo por mes.
                if (ultimoMesOtorgado != null && !mes.isAfter(ultimoMesOtorgado)) continue

                val cupo = calcularCupo(t.socioId, mes)
                val disponible = cupo.disponible
                val cajaDisp = redondear2(fondo - utilidadPendiente)

                t.mesesProyectados = cupo.meses
                t.aportesProyectados = cupo.aportesProyectados
                t.capacidadMax = cupo.capacidadMax
                t.saldoProyectado = cupo.saldoProyectado
                t.disponible = disponible
                t.cajaDisponible = cajaDisp

                // Modo "préstamos parciales": el fondo otorga en su mes de turno lo que
                // puede, aunque sea menos que el cupo completo del socio.
                val monto = if (snap.prestamosParciales) {
                    val parcial = redondear2(minOf(disponible, cajaDisp))
                    // No se otorga menos que el saldo del préstamo viejo: sería condonar deuda.
                    if (parcial > 0 && parcial >= cupo.saldoProyectado) parcial else 0.0
                } else {
                    if (disponible > 0 && cajaDisp >= disponible) disponible else 0.0
                }

                if (monto > 0) {
                    fondo = redondear2(fondo - monto)
                    desembolsosDelMes = redondear2(desembolsosDelMes + monto)
                    t.estado = "otorgado"
                    t.mesOtorgado = mes
                    t.mesesExtra = (mes.year - t.mesTurno.year) * 12 + (mes.monthValue - t.mesTurno.monthValue)
                    t.montoOtorgado = monto
                    t.prestamoParcial = monto < disponible
                    // El préstamo viejo se recoge: sus cuotas dejan de entrar al fondo.
                    recogidoEn[t.socioId] = mes
                    ultimoMesOtorgado = mes
                    programarCuotasSimuladas(
                        monto, snap.tasaDefault, snap.numCuotasSimulacion, mes, nuevasCuotas,
                    )
                }
            }

            // 5. Liquidación de utilidad (junio y diciembre): sale de la caja en efectivo.
            //    Solo se reparte si HAY CAJA para pagarla; si no, se pospone y la
            //    utilidad sigue acumulada para el siguiente cierre.
            var utilidadAPagar = 0.0
            var esLiquidacion = mes.monthValue == 6 || mes.monthValue == 12
            var cubierta = true
            val cajaAntesLiquidacion = fondo
            if (esLiquidacion) {
                if (fondo >= utilidadPendiente) {
                    utilidadAPagar = utilidadPendiente
                    fondo = redondear2(fondo - utilidadPendiente)
                    utilidadPendiente = 0.0
                } else {
                    cubierta = false
                }
                liquidaciones.add(
                    LiquidacionItem(
                        periodo = mes.toString(),
                        utilidad = redondear2(if (cubierta) utilidadAPagar else utilidadPendiente),
                        cajaAntes = cajaAntesLiquidacion,
                        cajaDespues = fondo,
                        cubierta = cubierta,
                    )
                )
            }

            // 6. Registro del mes para la proyección de la caja
            mesesFondo.add(
                FondoMesItem(
                    periodo = mes.toString(),
                    fondoInicial = fondoInicial,
                    aportes = aporteTotalMensual.let { if (mes.monthValue == 12) 0.0 else it },
                    cobros = redondear2(cobros),
                    utilidadDevengada = redondear2(interesMes),
                    utilidadAPagar = redondear2(utilidadAPagar),
                    utilidadSinRepartir = redondear2(utilidadPendiente),
                    desembolsos = redondear2(desembolsosDelMes),
                    fondoFinal = fondo,
                    cajaDisponible = redondear2(fondo - utilidadPendiente),
                    esLiquidacion = esLiquidacion,
                    esGracia = mes.monthValue == 12,
                )
            )

            mes = mes.plusMonths(1)
        }

        return SimulacionCapacidad(
            items = turnos.map { t ->
                CapacidadPrestamoItem(
                    socioId = t.socioId,
                    nombre = t.nombre,
                    turno = t.turno,
                    mesTurno = t.mesTurno.toString(),
                    anclaPeriodo = ancla.toString(),
                    mesesProyectados = t.mesesProyectados,
                    aportes = redondear2(totalAportesSocio(snap, t.socioId)),
                    aportesProyectados = t.aportesProyectados,
                    capacidadMax = t.capacidadMax,
                    prestamoActivoSaldo = redondear2(totalPrestamoPendienteSocio(snap, t.socioId)),
                    prestamoActivoSaldoProyectado = t.saldoProyectado,
                    disponible = t.disponible,
                    fondoProyectadoTurno = t.fondoEnTurno ?: 0.0,
                    cajaDisponible = t.cajaDisponible,
                    disponibleEnTurno = t.disponibleEnTurno,
                    cajaDisponibleEnTurno = t.cajaDisponibleEnTurno,
                    prestamoIdealEnTurno = t.prestamoIdealEnTurno,
                    brechaEnTurno = t.brechaEnTurno,
                    aportesProyectadosEnTurno = t.aportesProyectadosEnTurno,
                    capacidadMaxEnTurno = t.capacidadMaxEnTurno,
                    prestamoActivoSaldoProyectadoEnTurno = t.saldoProyectadoEnTurno,
                    montoOtorgado = t.montoOtorgado,
                    prestamoParcial = t.prestamoParcial,
                    alcanzaFondo = t.estado == "otorgado",
                    mesesExtra = t.mesesExtra,
                    mesAlcance = t.mesOtorgado?.toString(),
                )
            },
            mesesFondo = mesesFondo,
            liquidaciones = liquidaciones,
            ancla = ancla,
        )
    }

    /**
     * Genera las cuotas del préstamo nuevo que se simula: `n` cuotas iguales (método
     * francés) que empiezan el mes siguiente al desembolso y saltan diciembre (mes de
     * gracia). Esas cuotas vuelven al fondo, así que el préstamo devuelve capital e
     * interés con el correr de los meses.
     */
    private fun programarCuotasSimuladas(
        monto: Double,
        tasa: Double,
        n: Int,
        desdeMes: YearMonth,
        destino: MutableMap<YearMonth, CuotaSimulada>,
    ) {
        if (monto <= 0 || n <= 0) return
        val cuotaFija = if (tasa == 0.0) monto / n else monto * tasa / (1 - Math.pow(1 + tasa, -n.toDouble()))
        var saldo = monto
        var mes = desdeMes
        var pagadas = 0
        while (pagadas < n) {
            mes = mes.plusMonths(1)
            if (mes.monthValue == 12) continue // diciembre: mes de gracia
            pagadas++
            val interes = redondear2(saldo * tasa)
            var capital = redondear2(cuotaFija - interes)
            if (pagadas == n) capital = redondear2(saldo) // la última cuota ajusta el saldo
            saldo = redondear2(maxOf(0.0, saldo - capital))
            val cuota = if (pagadas == n) redondear2(capital + interes) else redondear2(cuotaFija)
            val previo = destino[mes] ?: CuotaSimulada(0.0, 0.0, 0.0)
            destino[mes] = CuotaSimulada(
                redondear2(previo.cuota + cuota),
                redondear2(previo.interes + interes),
                redondear2(previo.capital + capital),
            )
        }
    }

    /** Cuota de un préstamo vigente (solo las que faltan por pagar). */
    private data class CuotaFutura(
        val periodo: YearMonth,
        val cuota: Double,
        val interes: Double,
        val capital: Double,
    )

    /** Cupo de un socio calculado para un mes concreto de la simulación. */
    private data class CupoSim(
        val meses: Int,
        val aportesProyectados: Double,
        val capacidadMax: Double,
        val saldoProyectado: Double,
        val disponible: Double,
    )

    /** Cuota de un préstamo simulado (el nuevo que se otorga en un turno). */
    private data class CuotaSimulada(
        val cuota: Double,
        val interes: Double,
        val capital: Double,
    )

    /** Estado de un socio dentro de la simulación de capacidad. */
    private class TurnoSim(
        val socioId: String,
        val nombre: String,
        val turno: Int,
        val mesTurno: YearMonth,
    ) {
        var estado: String = "pendiente"
        var mesOtorgado: YearMonth? = null
        var mesesExtra: Int = 0
        var mesesProyectados: Int = 0
        var aportesProyectados: Double = 0.0
        var capacidadMax: Double = 0.0
        var saldoProyectado: Double = 0.0
        var disponible: Double = 0.0
        var cajaDisponible: Double = 0.0
        var fondoEnTurno: Double? = null
        var tieneDatosDeTurno: Boolean = false
        var cajaDisponibleEnTurno: Double = 0.0
        var disponibleEnTurno: Double = 0.0
        var prestamoIdealEnTurno: Double = 0.0
        var brechaEnTurno: Double = 0.0
        var aportesProyectadosEnTurno: Double = 0.0
        var capacidadMaxEnTurno: Double = 0.0
        var saldoProyectadoEnTurno: Double = 0.0
        var montoOtorgado: Double = 0.0
        var prestamoParcial: Boolean = false
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