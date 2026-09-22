package com.perseo.rgc.controller

import com.perseo.rgc.dto.PagoCuotaRequest
import com.perseo.rgc.dto.PrestamoRequest
import com.perseo.rgc.dto.RegenerarTablaRequest
import com.perseo.rgc.model.Prestamo
import com.perseo.rgc.repository.PrestamoRepository
import com.perseo.rgc.service.AmortizacionService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/prestamos")
class PrestamoController(
    private val prestamoRepository: PrestamoRepository,
    private val amortizacionService: AmortizacionService,
) {

    @GetMapping
    fun listar(@RequestParam(required = false) socioId: String?): List<Prestamo> =
        if (socioId != null) prestamoRepository.findBySocioId(socioId) else prestamoRepository.findAll()

    @GetMapping("/{id}")
    fun obtener(@PathVariable id: String): ResponseEntity<Prestamo> =
        prestamoRepository.findById(id).map { ResponseEntity.ok(it) }.orElse(ResponseEntity.notFound().build())

    @PostMapping
    fun crear(@RequestBody req: PrestamoRequest): Prestamo {
        val cuotas = amortizacionService.calcular(req.monto, req.tasa, req.numCuotas, req.fechaInicio)
        val prestamo = Prestamo(
            socioId = req.socioId, monto = req.monto, tasa = req.tasa, numCuotas = req.numCuotas,
            fechaInicio = req.fechaInicio, estado = "activo", cuotas = cuotas,
        )
        return prestamoRepository.save(prestamo)
    }

    @PostMapping("/{id}/generar-tabla")
    fun generarTabla(@PathVariable id: String, @RequestBody req: RegenerarTablaRequest): ResponseEntity<Prestamo> {
        val prestamo = prestamoRepository.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        val saldoBase = amortizacionService.saldoPendiente(prestamo)
        prestamo.tasa = req.tasa
        prestamo.numCuotas = req.numCuotas
        prestamo.fechaInicio = req.fechaInicio
        prestamo.cuotas = amortizacionService.calcular(saldoBase, req.tasa, req.numCuotas, req.fechaInicio)
        return ResponseEntity.ok(prestamoRepository.save(prestamo))
    }

    @PostMapping("/{id}/cuotas/{numero}/pagar")
    fun pagarCuota(
        @PathVariable id: String,
        @PathVariable numero: Int,
        @RequestBody req: PagoCuotaRequest,
    ): ResponseEntity<Prestamo> {
        val prestamo = prestamoRepository.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        val cuota = prestamo.cuotas.find { it.numero == numero } ?: return ResponseEntity.badRequest().build()
        cuota.pagado = true
        cuota.fechaPago = req.fechaPago
        cuota.montoPagado = req.montoPagado
        if (prestamo.cuotas.all { it.pagado }) prestamo.estado = "pagado"
        return ResponseEntity.ok(prestamoRepository.save(prestamo))
    }

    @DeleteMapping("/{id}")
    fun eliminar(@PathVariable id: String): ResponseEntity<Void> {
        prestamoRepository.deleteById(id)
        return ResponseEntity.noContent().build()
    }
}
