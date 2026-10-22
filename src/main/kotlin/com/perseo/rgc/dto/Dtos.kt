package com.perseo.rgc.dto

import java.time.LocalDate

data class SocioRequest(
    val nombre: String,
    val activo: Boolean = true,
    val fechaIngreso: LocalDate = LocalDate.now(),
)

data class AporteRequest(
    val socioId: String,
    val periodo: String,
    val fecha: LocalDate,
    val monto: Double,
    val nota: String = "",
)

data class PrestamoRequest(
    val socioId: String,
    val monto: Double,
    val tasa: Double,
    val numCuotas: Int,
    val fechaInicio: LocalDate,
    val aplicarGraciaDiciembre: Boolean = true,
)

data class RegenerarTablaRequest(
    val tasa: Double,
    val numCuotas: Int,
    val fechaInicio: LocalDate,
    val aplicarGraciaDiciembre: Boolean = true,
)

data class PagoCuotaRequest(
    val fechaPago: LocalDate,
    val montoPagado: Double,
)

data class GastoRequest(
    val concepto: String,
    val periodo: String,
    val fecha: LocalDate,
    val monto: Double,
)

data class LiquidarUtilidadRequest(
    val periodo: String,
)

data class ErrorResponse(val error: String)

data class ConfigRequest(
    val tasaDefault: Double,
    val ajusteInteresesHistoricos: Double = 0.0,
    val aporteMensualSocio: Double = 200000.0,
    val usarUltimoAporteReal: Boolean = false,
    val numCuotasSimulacion: Int = 18,
    val prestamosParciales: Boolean = false,
)

data class SocioSaldoResponse(
    val socioId: String,
    val nombre: String,
    val activo: Boolean,
    val aportes: Double,
    val prestamoPendiente: Double,
    val utilidadRecibida: Double,
    val saldoNeto: Double,
)

data class ResumenResponse(
    val bancos: Double,
    val aportesTotales: Double,
    val prestamosPendientes: Double,
    val utilidadSinLiquidar: Double,
    val interesesMesActual: Double,
    val saldosPorSocio: List<SocioSaldoResponse>,
)

data class UtilidadPeriodoResponse(
    val periodo: String,
    val intereses: Double,
    val gastos: Double,
    val neta: Double,
    val distribucion: List<com.perseo.rgc.model.DistribucionItem>,
    val yaLiquidado: Boolean,
)

data class ProyeccionMesResponse(
    val periodo: String,
    val interesesMes: Double,
    val utilidadMes: Double,
    val acumUtilidad: Double,
    val acumAportes: Double,
)

/**
 * Una fila del reporte de "Capacidad de préstamo por turno".
 *
 * Contexto de los campos (ver docs/capacidad-prestamos.md):
 * - [mesTurno] es el mes *calendario* del turno (un socio por mes desde enero 2027).
 * - [mesAlcance] es el mes *real* en que el fondo alcanza y el préstamo se otorga;
 *   puede ser posterior si el fondo no da (turnos encadenados).
 * - [aportes] es lo acumulado hoy; [aportesProyectados] es lo acumulado en el mes
 *   de otorgamiento (incluye los aportes mensuales proyectados, diciembre excluido).
 * - [prestamoActivoSaldo] es el saldo de hoy; [prestamoActivoSaldoProyectado] el saldo
 *   en el mes de otorgamiento (ya descontado el capital que el socio pagó antes).
 * - [cajaDisponible] es la caja menos la utilidad acumulada y todavía no repartida:
 *   lo que el fondo puede prestar sin deberle a sus propios socios.
 * - [disponibleEnTurno], [cajaDisponibleEnTurno] y [prestamoIdealEnTurno] son el
 *   panorama en el **mes calendario del turno**: cuánto le correspondía al socio,
 *   cuánto tinha el fondo en caja y el menor de los dos (el préstamo que el fondo
 *   podía hacerle ahí). [brechaEnTurno] es lo que le faltó para el cupo completo.
 * - [montoOtorgado] es lo que el fondo le otorga finalmente; [prestamoParcial] indica
 *   que fue menos que su cupo completo (modo "préstamos parciales").
 */
data class CapacidadPrestamoItem(
    val socioId: String,
    val nombre: String,
    val turno: Int,
    val mesTurno: String,
    val anclaPeriodo: String,
    val mesesProyectados: Int,
    val aportes: Double,
    val aportesProyectados: Double,
    val capacidadMax: Double,
    val prestamoActivoSaldo: Double,
    val prestamoActivoSaldoProyectado: Double,
    val disponible: Double,
    val fondoProyectadoTurno: Double,
    val cajaDisponible: Double,
    // --- panorama en el mes calendario del turno ---
    val disponibleEnTurno: Double,
    val cajaDisponibleEnTurno: Double,
    val prestamoIdealEnTurno: Double,
    val brechaEnTurno: Double,
    val aportesProyectadosEnTurno: Double,
    val capacidadMaxEnTurno: Double,
    val prestamoActivoSaldoProyectadoEnTurno: Double,
    // --- resultado ---
    val montoOtorgado: Double,
    val prestamoParcial: Boolean,
    val alcanzaFondo: Boolean,
    val mesesExtra: Int,
    val mesAlcance: String? = null,
)

/**
 * Un mes de la proyección de la caja del fondo.
 *
 * Muestra de dónde viene y a dónde va cada peso: lo que entra (aportes + cobros de
 * cuotas) y lo que sale (préstamos otorgados + utilidad repartida a los socios).
 * [cajaDisponible] es lo que queda después de reservar la utilidad que todavía no
 * se ha repartido: el máximo que el fondo puede prestar sin deberle a sus socios.
 */
data class FondoMesItem(
    val periodo: String,              // "2027-06"
    val fondoInicial: Double,
    val aportes: Double,              // aportes que entran (0 en diciembre)
    val cobros: Double,               // cuotas cobradas (capital + interés)
    val utilidadDevengada: Double,    // interés acumulado en el mes
    val utilidadAPagar: Double,       // utilidad que sale del fondo (0 si no es mes de cierre)
    val utilidadSinRepartir: Double,  // utilidad acumulada al cierre del mes
    val desembolsos: Double,          // cuánto se prestó en el mes
    val fondoFinal: Double,
    val cajaDisponible: Double,
    val esLiquidacion: Boolean,
    val esGracia: Boolean,            // diciembre
)

/** Una liquidación de utilidad proyectada (junio y diciembre). */
data class LiquidacionItem(
    val periodo: String,
    val utilidad: Double,
    val cajaAntes: Double,
    val cajaDespues: Double,
    /** false = no había caja suficiente, la liquidación se pospone. */
    val cubierta: Boolean,
)

/** Proyección de la caja del fondo mes a mes. */
data class FondoProyeccionResponse(
    val anclaPeriodo: String,
    val meses: List<FondoMesItem>,
    val liquidaciones: List<LiquidacionItem>,
    /** La primera liquidación que viene (la más cercana). */
    val proximaLiquidacion: LiquidacionItem? = null,
)