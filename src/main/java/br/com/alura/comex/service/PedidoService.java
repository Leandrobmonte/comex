package br.com.alura.comex.service;

import br.com.alura.comex.config.exception.BussinesException;
import br.com.alura.comex.config.exception.ExceptionEntidadeNaoEncontrada;
import br.com.alura.comex.model.ItemDePedido;
import br.com.alura.comex.model.Pedido;
import br.com.alura.comex.model.Produto;
import br.com.alura.comex.model.TipoDesconto;
import br.com.alura.comex.model.TipoDescontoItem;
import br.com.alura.comex.model.Usuario;
import br.com.alura.comex.model.dto.input.ItemPedidoDto;
import br.com.alura.comex.model.dto.input.PedidoInputDto;
import br.com.alura.comex.model.dto.output.PedidoDetalheOutputDto;
import br.com.alura.comex.model.dto.output.PedidoNovoOutputDto;
import br.com.alura.comex.model.dto.output.PedidoOutputDto;
import br.com.alura.comex.model.dto.projecao.PedidoProjecao;
import br.com.alura.comex.repository.PedidoRepository;
import br.com.alura.comex.repository.ProdutoRepository;
import br.com.alura.comex.repository.UsuarioRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class PedidoService {

    private final PedidoRepository pedidoRepository;
    private final ProdutoRepository produtoRepository;
    private final UsuarioRepository usuarioRepository;
    private final ClienteService clienteService;
    private final ItemDePedidosService itemDePedidosService;
    private static final Integer MINIMO_DE_PEDIDO_PARA_FIDELIDADE = 5;
    private static final Integer MINIMO_DE_QUANTIDADE_ITEMS_PRODUTOS_PARA_DESCONTO = 10;
    private static final Double DEZ_PORCENTO = 0.1;
    private static final Double CINCO_PORCENTO = 0.05;

    private static final String MSG_PEDIDO_NAO_ENCONTRADO
            = "Não existe um cadastro de categoria com o código %d";


    public PedidoService(PedidoRepository pedidoRepository,
                         ProdutoRepository produtoRepository,
                         UsuarioRepository usuarioRepository,
                         ClienteService clienteService,
                         ItemDePedidosService itemDePedidosService) {
        this.pedidoRepository = pedidoRepository;
        this.produtoRepository = produtoRepository;
        this.usuarioRepository = usuarioRepository;
        this.clienteService = clienteService;
        this.itemDePedidosService = itemDePedidosService;
    }

    public List<PedidoProjecao> pedidosPorCategoria() {
        List<PedidoProjecao> list = pedidoRepository.pedidosPorCategorias();
        return list;
    }

    @Transactional
    public PedidoNovoOutputDto cadastrar(PedidoInputDto pedidoInputDto) {
        try {
            //valida se existe estoque para produtos listados.
            List<Produto> produtos = this.validaQuantidadeDeEstoqueProduto(pedidoInputDto);
            Pedido pedido = this.validaDescontosDoPedido(pedidoInputDto);
            List<ItemDePedido> listaItemDePedido = this.emitirItemPedido(pedidoInputDto, produtos, pedido);
            pedidoRepository.save(pedido);
            produtoRepository.saveAll(produtos);
            for (ItemDePedido itemPedido : listaItemDePedido) {
                itemDePedidosService.salvar(itemPedido);
            }
            return new PedidoNovoOutputDto(pedido);
        } catch (BussinesException e) {
            throw new BussinesException(e.getMessage());
        }
    }

    private List<Produto> validaQuantidadeDeEstoqueProduto(PedidoInputDto pedidoInputDto){
        List<Optional<Produto>> produtosOptional = new ArrayList<>();
        List<Produto> produtos = new ArrayList<>();
        for(ItemPedidoDto itemPedidoDto : pedidoInputDto.getItemPedidos()){
            produtosOptional.add(produtoRepository.findById(itemPedidoDto.getProdutoId()));
        }
        produtosOptional.stream().forEach(produtoOptional -> {
            if(produtoOptional.isPresent()){
                Produto produto = produtoOptional.get();
                for(ItemPedidoDto prodPedido:pedidoInputDto.getItemPedidos()){
                    if(prodPedido.getProdutoId() == produto.getId()){
                        if( produto.getQuantidadeEstoque() < prodPedido.getQuantidadeVendida()){
                            throw new BussinesException(String.format("Produto %s sem estoque suficiente.", produto.getNome()));
                        }
                        produto.setQuantidadeEstoque(produto.getQuantidadeEstoque() - prodPedido.getQuantidadeVendida());
                        produtos.add(produto);
                    }
                }
            }
        });

        return produtos;
    }

    private Pedido validaDescontosDoPedido(PedidoInputDto pedidoInputDto){
        Pedido pedido = new Pedido();
        Boolean descontoPorQuantidade = pedidoInputDto.getItemPedidos().stream().anyMatch(prodQtd -> prodQtd.getQuantidadeVendida() > MINIMO_DE_QUANTIDADE_ITEMS_PRODUTOS_PARA_DESCONTO);
        Boolean descontoPorFidelidade = pedidoRepository.countByClienteId(pedidoInputDto.getClienteId()) >= MINIMO_DE_PEDIDO_PARA_FIDELIDADE;

        pedido.setDesconto(BigDecimal.valueOf(0.0));
        pedido.setCliente(clienteService.clientePorId(pedidoInputDto.getClienteId()).get());
        pedido.setTipoDesconto(TipoDesconto.NENHUM);

        //Valida descontos
        if(descontoPorQuantidade && !descontoPorFidelidade){
            pedido.setDesconto(BigDecimal.valueOf(DEZ_PORCENTO));
        }
        if(descontoPorFidelidade && !descontoPorQuantidade){
            pedido.setDesconto(BigDecimal.valueOf(CINCO_PORCENTO));
            pedido.setTipoDesconto(TipoDesconto.FIDELIDADE);
        }
        if(descontoPorFidelidade && descontoPorQuantidade){
            pedido.setDesconto(BigDecimal.valueOf(CINCO_PORCENTO).add(BigDecimal.valueOf(DEZ_PORCENTO)));
            pedido.setTipoDesconto(TipoDesconto.FIDELIDADE);
        }
        return pedido;
    }

    private List<ItemDePedido> emitirItemPedido(PedidoInputDto pedidoInputDto, List<Produto> produtos, Pedido pedido){

        List<ItemDePedido> listaItemDePedido = new ArrayList<>();
        Boolean descontoPorQuantidade = pedidoInputDto.getItemPedidos().stream().anyMatch(prodQtd -> prodQtd.getQuantidadeVendida() > MINIMO_DE_QUANTIDADE_ITEMS_PRODUTOS_PARA_DESCONTO);
        for (ItemPedidoDto ipd : pedidoInputDto.getItemPedidos()) {
            for (Produto p : produtos) {
                if (ipd.getProdutoId() == p.getId()) {
                    ItemDePedido itemDePedido = new ItemDePedido(ipd.getQuantidadeVendida(), p);
                    itemDePedido.setTipoDesconto(TipoDescontoItem.NENHUM);
                    itemDePedido.setPedido(pedido);
                    if (descontoPorQuantidade) {
                        itemDePedido.setTipoDesconto(TipoDescontoItem.QUANTIDADE);
                    }
                    BigDecimal porcentagem = pedido.getDesconto();
                    itemDePedido.setDesconto(itemDePedido.getValorTotalItem().multiply(porcentagem));
                    listaItemDePedido.add(itemDePedido);
                }
            }
        }
        return listaItemDePedido;
    }

    public Page<PedidoOutputDto> listaPedidos(Pageable paginacao) {
        Page<Pedido> pedidos = pedidoRepository.findAll(paginacao);
        return PedidoOutputDto.converter(pedidos);
    }

    public PedidoDetalheOutputDto detalharPedido(Long id, Usuario logado) {
        Pedido pedido = this.buscarOuFalhar(id);
        PedidoDetalheOutputDto pedidoOutputDto = new PedidoDetalheOutputDto(pedido);
        validarUsuario(logado, pedido);
        return pedidoOutputDto;
    }

    private void validarUsuario(Usuario logado, Pedido pedido) {
        Usuario usuario = pedido.getCliente().getUsuario();
        if(logado.getId() != usuario.getId()){
            throw new BussinesException("Este pedido pertence a outro usuário");
        }
    }

    public Pedido buscarOuFalhar(Long pedidoId){
        return pedidoRepository
                .findById(pedidoId)
                .orElseThrow(() ->
                        new ExceptionEntidadeNaoEncontrada(
                                String.format(MSG_PEDIDO_NAO_ENCONTRADO, pedidoId)
                        )
                );
    }
}
