package br.com.alura.comex.repository;

import br.com.alura.comex.model.Produto;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProdutoRepository extends PagingAndSortingRepository<Produto,Long> {

}
